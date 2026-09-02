package io.vanillabp.integration.adapter.spi.version;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bookkeeping every {@link ProcessVersionCatalog} needs, so an adapter only has to
 * answer "which versions of this process does the BPMS have right now" - see
 * {@link #fetchDeployedVersions(String, String)}.
 * <p>
 * What is kept here:
 * <ul>
 * <li>the versions learned from a BPMS query and the versions the adapter
 * {@link #record(String, String, DeployedProcessVersion) recorded} from the result of
 * its deploy command - the deploy command names the version the BPMS assigned to the
 * model deployed right now, which the query would have to be asked for otherwise;</li>
 * <li>the on-demand query for a version identifier not seen yet: during a rolling
 * deployment another cluster node may deploy a new version before this node does, and
 * the BPMS delivers a task of that version to this node meanwhile;</li>
 * <li>a floor between two queries for the SAME unknown value
 * ({@value #DEFAULT_REFRESH_INTERVAL_SECONDS} seconds by default), so a version
 * specification naming a tag which does not exist cannot turn every task delivery into a
 * BPMS query, while a version showing up for the first time is looked up right away.</li>
 * </ul>
 * A query failing is not an error of the running workflow: it is logged and the version
 * stays unknown, which lets the version specifications made of numbers keep working.
 */
public abstract class CachingProcessVersionCatalog implements ProcessVersionCatalog {

  private static final Logger log = LoggerFactory.getLogger(CachingProcessVersionCatalog.class);

  /**
   * How long a version identifier or tag stays unknown before the BPMS is asked about
   * it again.
   */
  public static final int DEFAULT_REFRESH_INTERVAL_SECONDS = 60;

  private static class Versions {

    /**
     * By version identifier, in deployment order (oldest first).
     */
    private final Map<String, DeployedProcessVersion> byVersion = new LinkedHashMap<>();

    /**
     * By version tag - the NEWEST version carrying it, since a tag may be reused by a
     * later deployment.
     */
    private final Map<String, DeployedProcessVersion> byVersionTag = new HashMap<>();

    private Instant fetchedAt;

    /**
     * When the BPMS was last asked about a version identifier or tag it did not know -
     * per value, so a version showing up for the first time is looked up right away
     * while a tag which does not exist cannot be asked for over and over.
     */
    private final Map<String, Instant> missedAt = new HashMap<>();

    private DeployedProcessVersion lookup(
        final String versionOrVersionTag) {

      final var byVersionHit = byVersion.get(versionOrVersionTag);
      return byVersionHit != null
          ? byVersionHit
          : byVersionTag.get(versionOrVersionTag);

    }

    private void add(
        final DeployedProcessVersion version) {

      byVersion.put(version.version(), version);
      if (version.versionTag() != null) {
        byVersionTag.put(version.versionTag(), version);
      }

    }

  }

  private final Map<String, Versions> versionsByProcess = new ConcurrentHashMap<>();

  private final Duration refreshInterval;

  protected CachingProcessVersionCatalog() {

    this(Duration.ofSeconds(DEFAULT_REFRESH_INTERVAL_SECONDS));

  }

  protected CachingProcessVersionCatalog(
      final Duration refreshInterval) {

    this.refreshInterval = refreshInterval;

  }

  /**
   * Asks the BPMS for all versions of the given process, oldest first. Called at
   * startup for every process whose annotations name a version tag, and at runtime
   * whenever a version identifier or tag is not known yet.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @return The deployed versions, oldest first; empty if the BPMS cannot tell
   */
  protected abstract List<DeployedProcessVersion> fetchDeployedVersions(
      String workflowModuleId,
      String bpmnProcessId);

  /**
   * Adds a version the adapter learned without asking the BPMS - the result of the
   * deploy command reports the version the BPMS assigned to the model just deployed,
   * and the model itself carries its version tag.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The PLAIN BPMN process ID
   * @param version The deployed version
   */
  public void record(
      final String workflowModuleId,
      final String bpmnProcessId,
      final DeployedProcessVersion version) {

    if (version == null) {
      return;
    }
    final var versions = versionsOf(workflowModuleId, bpmnProcessId);
    synchronized (versions) {
      versions.add(version);
    }

  }

  @Override
  public List<DeployedProcessVersion> deployedVersionsOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var versions = versionsOf(workflowModuleId, bpmnProcessId);
    synchronized (versions) {
      if (versions.fetchedAt == null) {
        fetch(versions, workflowModuleId, bpmnProcessId);
      }
      return List.copyOf(versions.byVersion.values());
    }

  }

  @Override
  public DeployedProcessVersion resolveVersion(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String versionOrVersionTag) {

    if (versionOrVersionTag == null) {
      return null;
    }
    final var versions = versionsOf(workflowModuleId, bpmnProcessId);
    synchronized (versions) {
      final var known = versions.lookup(versionOrVersionTag);
      if (known != null) {
        return known;
      }
      // not seen yet: another cluster node may have deployed a version this node
      // does not know of (rolling deployment), so the BPMS is asked - but not once
      // per task delivery, which is what the interval per value is for
      final var missedAt = versions.missedAt.get(versionOrVersionTag);
      if ((missedAt != null) && Instant.now().isBefore(missedAt.plus(refreshInterval))) {
        return null;
      }
      versions.missedAt.put(versionOrVersionTag, Instant.now());
      fetch(versions, workflowModuleId, bpmnProcessId);
      final var found = versions.lookup(versionOrVersionTag);
      if (found != null) {
        versions.missedAt.remove(versionOrVersionTag);
      }
      return found;
    }

  }

  private void fetch(
      final Versions versions,
      final String workflowModuleId,
      final String bpmnProcessId) {

    versions.fetchedAt = Instant.now();
    final List<DeployedProcessVersion> fetched;
    try {
      fetched = fetchDeployedVersions(workflowModuleId, bpmnProcessId);
    } catch (final RuntimeException e) {
      log.warn(
          "Could not determine the deployed versions of BPMN process '{}' of workflow module "
              + "'{}' - version specifications naming a version tag do not match until the next "
              + "attempt",
          bpmnProcessId,
          workflowModuleId,
          e);
      return;
    }
    if (fetched == null) {
      return;
    }
    // the fetched order is the deployment order and replaces what was recorded from
    // deploy results before - those are part of the fetched list anyway
    final var recorded = new ArrayList<>(versions.byVersion.values());
    versions.byVersion.clear();
    versions.byVersionTag.clear();
    fetched.forEach(versions::add);
    recorded
        .stream()
        .filter(version -> !versions.byVersion.containsKey(version.version()))
        .forEach(versions::add);

  }

  private Versions versionsOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return versionsByProcess.computeIfAbsent(
        workflowModuleId
            + "|"
            + bpmnProcessId,
        key -> new Versions());

  }

}
