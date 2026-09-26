package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.version.ReportedProcessVersion;

/**
 * What the BPMS of every adapter knows about the deployed versions of the BPMN
 * processes, per (workflow module, BPMN process) - registered by the adapters during
 * <code>wireBpmn</code> (see
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#registerProcessVersions})
 * and used to place a version TAG named by <code>&#64;WorkflowTask(version = ...)</code>
 * and its siblings in the deployment order.
 * <p>
 * A version specification made of numbers never gets here: it is compared to the
 * version the adapter reported. That is what keeps the cost of the feature at zero for
 * applications not using version tags.
 * <p>
 * While a BPMS migration is running, two adapters may serve the same BPMN process. Each
 * BPMS counts its own versions, so the catalogs are asked in registration order and the
 * first one knowing the version or tag answers.
 * <p>
 * What a version is, and why overlapping ranges end the start, is decision 20 in the repository's
 * DECISIONS.md.
 */
public class ProcessVersions {

  private static final Logger log = LoggerFactory.getLogger(ProcessVersions.class);

  private record RegistryKey(
                             String workflowModuleId,
                             String bpmnProcessId) {
  }

  /**
   * One BPMS answering for one BPMN process.
   *
   * @param adapterId The adapter ID
   * @param catalog What that BPMS knows about the process' versions
   */
  public record RegisteredCatalog(
                                  String adapterId,
                                  ProcessVersionCatalog catalog) {
  }

  private record DeploymentKey(
                               String adapterId,
                               String workflowModuleId,
                               String bpmnProcessId) {
  }

  /**
   * One BPMS which said it keeps no catalog for one BPMN process.
   *
   * @param adapterId The adapter ID
   * @param reported What a delivery of that BPMS carries as its process version
   */
  private record WithoutACatalog(
                                 String adapterId,
                                 ReportedProcessVersion reported) {
  }

  private final Map<RegistryKey, List<RegisteredCatalog>> catalogs = new ConcurrentHashMap<>();

  /**
   * The BPMS which said there is nothing to ask them about - see
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#reportNoProcessVersionCatalog}.
   * An adapter which says nothing is not in here, and that is the difference every
   * message below rests on.
   */
  private final Map<RegistryKey, List<WithoutACatalog>> withoutACatalog = new ConcurrentHashMap<>();

  /**
   * The version each adapter deployed during THIS boot - the border between
   * "the model this application brings" and the older versions the BPMS still holds.
   */
  private final Map<DeploymentKey, String> deployedVersions = new ConcurrentHashMap<>();

  /**
   * What was reported already, so a task delivery does not log the same message over and
   * over: the version identifiers and tags nobody could place, and the BPMN processes
   * whose BPMS keeps no catalog at all.
   */
  private final java.util.Set<String> reportedAsUnknown = ConcurrentHashMap.newKeySet();

  /**
   * Where a finding goes, so the whole start says it once.
   */
  private final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings;

  /**
   * Builds an empty registry - the {@link WorkflowTaskRegistry} keeps one of these, and a
   * test which only needs the version answers builds one of its own.
   * <p>
   * Nothing is known at this point. Everything in here arrives while the adapters wire their
   * BPMN, so the answers below are worth asking for only after the deployment.
   *
   * @param findings Where a finding of this registry is left
   */
  public ProcessVersions(
      final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings) {

    this.findings = findings;

  }

  /**
   * Registers what one BPMS knows about one BPMN process. Registering the same catalog
   * again (a module deployed at every boot, several workflow service classes) does not
   * duplicate it.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param catalog The versions of that process
   */
  public void register(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final ProcessVersionCatalog catalog) {

    if (catalog == null) {
      return;
    }
    final var registered = catalogs
        .computeIfAbsent(
            new RegistryKey(workflowModuleId, bpmnProcessId),
            key -> new CopyOnWriteArrayList<>());
    if (registered
        .stream()
        .noneMatch(existing -> existing.adapterId().equals(adapterId) && (existing.catalog() == catalog))) {
      registered.add(new RegisteredCatalog(adapterId, catalog));
    }

  }

  /**
   * Remembers that one BPMS keeps no catalog for one BPMN process, which is a statement
   * and not the absence of one.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param reported What a delivery of that BPMS carries as its process version
   */
  public void registerWithoutACatalog(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final ReportedProcessVersion reported) {

    if (reported == null) {
      return;
    }
    final var declared = withoutACatalog
        .computeIfAbsent(
            new RegistryKey(workflowModuleId, bpmnProcessId),
            key -> new CopyOnWriteArrayList<>());
    if (declared
        .stream()
        .noneMatch(existing -> existing.adapterId().equals(adapterId) && (existing.reported() == reported))) {
      declared.add(new WithoutACatalog(adapterId, reported));
    }

  }

  /**
   * What the BPMS of that process said about their missing catalog, and only where NONE
   * of them registered one. A process a second BPMS can be asked about is served by that
   * BPMS, so nothing is reported about the first.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @return The statements, empty where a catalog exists or nobody said anything
   */
  private List<WithoutACatalog> onlyWithoutACatalog(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var key = new RegistryKey(workflowModuleId, bpmnProcessId);
    final var registered = catalogs.get(key);
    if ((registered != null) && !registered.isEmpty()) {
      return List.of();
    }
    final var declared = withoutACatalog.get(key);
    return declared == null
        ? List.of()
        : List.copyOf(declared);

  }

  /**
   * What a delivery of the BPMS serving that process can carry as its process version -
   * the most a method can hope for, so two BPMS which answer differently leave the
   * method with the wider answer.
   */
  private static ReportedProcessVersion widestOf(
      final List<WithoutACatalog> declared) {

    return declared
        .stream()
        .anyMatch(statement -> statement.reported() == ReportedProcessVersion.VERSION_TAG)
            ? ReportedProcessVersion.VERSION_TAG
            : ReportedProcessVersion.NONE;

  }

  /**
   * Remembers the version an adapter deployed during this boot - see
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#registerDeployedVersion}.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param version The version identifier the BPMS assigned, or <code>null</code>
   */
  public void recordDeployedVersion(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String version) {

    if (version == null) {
      return;
    }
    deployedVersions.put(new DeploymentKey(adapterId, workflowModuleId, bpmnProcessId), version);

  }

  /**
   * The border between the model this boot brought and the older versions the BPMS holds.
   * <p>
   * A <code>null</code> answer carries two meanings, and only the caller can tell them apart:
   * where the module deployed the process there is no older version to speak of, and where
   * the id was declared without a model every version the BPMS holds under it is an older one
   * (decision 15 in the repository's DECISIONS.md).
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @return The version that adapter deployed during this boot, or <code>null</code>
   */
  public String deployedVersion(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return deployedVersions.get(new DeploymentKey(adapterId, workflowModuleId, bpmnProcessId));

  }

  /**
   * Which BPMS can be asked about that process at all.
   * <p>
   * The order is the order the adapters registered in, and it is what decides the answer
   * while two BPMS serve one process during a migration: the first one knowing a version or
   * tag wins. An empty answer means nobody can be asked, which is not the same as "no version
   * is deployed".
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @return The BPMS answering for that process, in registration order
   */
  public List<RegisteredCatalog> registeredCatalogs(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var registered = catalogs.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    return registered == null
        ? List.of()
        : List.copyOf(registered);

  }

  /**
   * A resolver for one BPMN process, to be handed to a {@link VersionRange}.
   * <p>
   * The resolver asks the registered catalogs and warns about a version or tag none of them
   * knows, once per name. So it belongs where an unknown name is worth a word - a plain
   * comparison of numbers needs no resolver at all.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @return Resolves version identifiers and version tags of that BPMN process
   */
  public VersionRange.ProcessVersionResolver resolverFor(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return versionOrVersionTag -> resolve(workflowModuleId, bpmnProcessId, versionOrVersionTag);

  }

  private io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion resolve(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String versionOrVersionTag) {

    final var resolved = lookup(workflowModuleId, bpmnProcessId, versionOrVersionTag);
    if (resolved != null) {
      return resolved;
    }
    reportUnknown(workflowModuleId, bpmnProcessId, versionOrVersionTag);
    return null;

  }

  /**
   * Asks the catalogs without reporting anything - the startup check reports in its own
   * words, naming the method whose specification cannot be served.
   */
  private io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion lookup(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String versionOrVersionTag) {

    final var registered = catalogs.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((registered != null) && !registered.isEmpty()) {
      // asking a catalog may query the BPMS: a version deployed by ANOTHER cluster
      // node is unknown here until it is asked for (the catalogs cache the answer)
      for (final var candidate : registered) {
        final var resolved = candidate
            .catalog()
            .resolveVersion(workflowModuleId, bpmnProcessId, versionOrVersionTag);
        if (resolved != null) {
          return resolved;
        }
      }
    }
    return null;

  }

  /**
   * Loads all versions of the given BPMN process the BPMS knows - called at startup
   * for the processes whose annotations name a version tag, so the tags are resolved
   * before the first workflow needs them.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   */
  public void warmUp(
      final String workflowModuleId,
      final String bpmnProcessId) {

    final var registered = catalogs.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if (registered == null) {
      return;
    }
    registered
        .forEach(candidate -> {
          final var versions = candidate.catalog().deployedVersionsOf(workflowModuleId, bpmnProcessId);
          log.debug(
              "Adapter '{}' knows {} deployed version(s) of BPMN process '{}' of workflow module '{}': {}",
              candidate.adapterId(),
              versions == null
                  ? 0
                  : versions.size(),
              bpmnProcessId,
              workflowModuleId,
              versions);
        });

  }

  /**
   * Reports a version tag no BPMS knows, ONCE, naming what the developer can do about
   * it. Not a boot failure: the tagged version may be deployed later (a rolling
   * deployment where another cluster node is ahead), and an application whose other
   * methods serve the deployed versions has to keep running.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param versionTag The version tag named by an annotation
   * @param describedLocation The method serving that specification, plus the
   *          declaration it came from where the method names none itself
   */
  public void reportUnknownVersionTag(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String versionTag,
      final String describedLocation) {

    if (lookup(workflowModuleId, bpmnProcessId, versionTag) != null) {
      return;
    }
    if (!onlyWithoutACatalog(workflowModuleId, bpmnProcessId).isEmpty()) {
      // "no BPMS knows that tag" reads as a typo, and on a BPMS which counts no
      // versions the tag is the one thing a delivery can carry. What such a process
      // needs to hear is said by reportMethodsWhichNeverRun, in its own words
      return;
    }
    findings
        .warn(
            io.vanillabp.integration.adapter.migration.startup.StartupTopic.DEPLOYED_VERSIONS,
            "process '%s' of workflow module '%s'".formatted(bpmnProcessId, workflowModuleId),
            """
                The version specification '%s' of %s names a version tag no BPMS knows for this \
                BPMN process! Until a version tagged that way is deployed, that method serves no \
                workflow. Check the tag against the BPMN model (Camunda 7: 'camunda:versionTag', \
                Camunda 8: 'zeebe:versionTag') - a version specification made of numbers (e.g. \
                '>2') needs no tag at all."""
                .formatted(versionTag, describedLocation));

  }

  private void reportUnknown(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String versionOrVersionTag) {

    final var key = "%s|%s|%s".formatted(workflowModuleId, bpmnProcessId, versionOrVersionTag);
    if (!reportedAsUnknown.add(key)) {
      return;
    }
    final var declared = onlyWithoutACatalog(workflowModuleId, bpmnProcessId);
    if (!declared.isEmpty()) {
      // the BPMS answered this while it was wired: there is no catalog to ask. Saying
      // that nobody can be asked would send the developer looking for an adapter which
      // was never missing
      findings
          .warn(
              io.vanillabp.integration.adapter.migration.startup.StartupTopic.DEPLOYED_VERSIONS,
              "process '%s' of workflow module '%s'".formatted(bpmnProcessId, workflowModuleId),
              """
                  The version specifications of the methods serving this BPMN process name '%s', \
                  and the BPMS of adapter %s keeps no catalog of the versions of that process. \
                  %s"""
                  .formatted(
                      versionOrVersionTag,
                      adapterIdsOf(declared),
                      whatSuchADeliveryCarries(widestOf(declared))));
      return;
    }
    final var registered = catalogs.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((registered == null) || registered.isEmpty()) {
      // saying "no versions are deployed" here would be wrong: the BPMS may well hold
      // versions of this process - what is missing is an adapter able to ASK about
      // them, which is a different defect with a different remedy
      findings
          .warn(
              io.vanillabp.integration.adapter.migration.startup.StartupTopic.DEPLOYED_VERSIONS,
              "process '%s' of workflow module '%s'".formatted(bpmnProcessId, workflowModuleId),
              """
                  The version specifications of the methods serving this BPMN process name '%s', \
                  but no adapter of this application can be asked which versions of that process \
                  its BPMS holds! Version specifications made of numbers (e.g. '1-3', '>2') work \
                  on every BPMS which reports the version of a process - version TAGS need a BPMS \
                  which can be asked about them."""
                  .formatted(versionOrVersionTag));
      return;
    }
    findings
        .warn(
            io.vanillabp.integration.adapter.migration.startup.StartupTopic.DEPLOYED_VERSIONS,
            "process '%s' of workflow module '%s'".formatted(bpmnProcessId, workflowModuleId),
            """
                Neither a deployed version nor a version tag '%s' of this BPMN process is known to \
                any BPMS - version specifications naming it match nothing until it is \
                deployed."""
                .formatted(versionOrVersionTag));

  }

  /**
   * Which methods of one BPMN process never run because the BPMS serving it keeps no
   * catalog of its versions - answered by the {@link WorkflowTaskRegistry}, the only
   * place knowing the methods and their specifications.
   */
  @FunctionalInterface
  public interface MethodsWhichNeverRun {

    /**
     * Names the methods of that process which no delivery of such a BPMS can reach.
     * <p>
     * This is asked only where a BPMS said it keeps no catalog, so the answer is a list for
     * one message and nothing else. An empty list keeps that message out altogether.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The plain BPMN process ID
     * @param reported What a delivery of that BPMS carries as its process version - what
     *          decides whether a specification can be met there at all
     * @return The methods, worded for the message, empty where every method can be reached
     */
    List<String> of(
        String workflowModuleId,
        String bpmnProcessId,
        ReportedProcessVersion reported);

  }

  /**
   * Names the methods of one BPMN process which never run because no BPMS serving it
   * keeps a catalog of its versions, ONCE per process, and says what would make them run.
   * <p>
   * Only a BPMS which SAID so gets here. Where an adapter simply registered no catalog,
   * nothing is reported: the two look the same from the core and they are not the same
   * thing, which is what
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#reportNoProcessVersionCatalog}
   * exists for.
   * <p>
   * A warning rather than the end of the boot: a method named here can be the right one
   * on another BPMS the application runs on, and the same code then serves both. Why the
   * core warns instead of refusing is decision 60 in the repository's DECISIONS.md.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param methods Which methods never run there
   */
  public void reportMethodsWhichNeverRun(
      final String workflowModuleId,
      final String bpmnProcessId,
      final MethodsWhichNeverRun methods) {

    final var declared = onlyWithoutACatalog(workflowModuleId, bpmnProcessId);
    if (declared.isEmpty()) {
      return;
    }
    final var reported = widestOf(declared);
    final var neverRunning = methods.of(workflowModuleId, bpmnProcessId, reported);
    if ((neverRunning == null) || neverRunning.isEmpty()) {
      return;
    }
    if (!reportedAsUnknown.add("%s|%s|no-catalog".formatted(workflowModuleId, bpmnProcessId))) {
      return;
    }
    findings
        .warn(
            io.vanillabp.integration.adapter.migration.startup.StartupTopic.DEPLOYED_VERSIONS,
            "process '%s' of workflow module '%s'".formatted(bpmnProcessId, workflowModuleId),
            """
                The BPMS of adapter %s keeps no catalog of the deployed versions of this BPMN \
                process, and no other BPMS of that process has one either. So %s never run there: \
                %s. %s A method naming no version serves every delivery, which is the short way \
                out. Keep the method where this application also runs on a BPMS counting versions: \
                it runs there, and this line stays a warning rather than the end of the boot."""
                .formatted(
                    adapterIdsOf(declared),
                    neverRunning.size() == 1
                        ? "this method does"
                        : "these %d methods do".formatted(neverRunning.size()),
                    String.join(", ", neverRunning),
                    whatSuchADeliveryCarries(reported)));

  }

  /**
   * What a delivery of a BPMS without a version catalog carries, written for the
   * developer reading either message about such a BPMS - the one at the start about the
   * methods which never run, and the one at a delivery naming a version nobody can place.
   * One wording, so the two cannot start contradicting each other.
   *
   * @param reported What such a delivery carries as its process version
   * @return The sentences saying what still works
   */
  private static String whatSuchADeliveryCarries(
      final ReportedProcessVersion reported) {

    return reported == ReportedProcessVersion.VERSION_TAG
        ? """
            A delivery of that BPMS carries the version tag of its model, where the engine fills \
            it. A version naming exactly that tag is met by such a delivery (e.g. version = \
            "release-2024"). A range is not: placing a version in a range needs the order the \
            versions were deployed in, and that order is what a catalog holds."""
        : """
            A delivery of that BPMS carries no process version at all, so a method naming any \
            version waits for something which never arrives.""";

  }

  /**
   * The adapters which answered, quoted for a message which names them.
   */
  private static String adapterIdsOf(
      final List<WithoutACatalog> declared) {

    return declared
        .stream()
        .map(WithoutACatalog::adapterId)
        .distinct()
        .map("'%s'"::formatted)
        .collect(java.util.stream.Collectors.joining(", "));

  }

}
