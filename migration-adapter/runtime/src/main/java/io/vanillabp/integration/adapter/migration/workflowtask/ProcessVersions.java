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
 * What the BPMS of every adapter knows about the deployed versions of the BPMN processes,
 * per workflow module and BPMN process. The adapters register it during
 * <code>wireBpmn</code> (see
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#registerProcessVersions}).
 * It is used to place a version TAG, named by <code>&#64;WorkflowTask(version = ...)</code>
 * and its siblings, in the order of the deployments.
 * <p>
 * A version specification made of numbers never gets here. It is compared with the
 * version which the adapter reported. That keeps the cost of the feature at zero for
 * applications which use no version tags.
 * <p>
 * While a BPMS migration runs, two adapters may serve the same BPMN process. Each BPMS
 * counts its own versions. So the catalogs are asked in the order they were registered,
 * and the first one which knows the version or tag answers.
 * <p>
 * Decision 20 in the repository's DECISIONS.md says what a version is, and why overlapping
 * ranges end the start.
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
   * One BPMS which said that it keeps no list of versions for one BPMN process.
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
   * The BPMS which said there is nothing to ask them about (see
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#reportNoProcessVersionCatalog}).
   * An adapter which says nothing is not in here. Every message below relies on that
   * difference.
   */
  private final Map<RegistryKey, List<WithoutACatalog>> withoutACatalog = new ConcurrentHashMap<>();

  /**
   * The version which each adapter deployed during THIS boot. It is the line between "the
   * model this application brings" and the older versions which the BPMS still holds.
   */
  private final Map<DeploymentKey, String> deployedVersions = new ConcurrentHashMap<>();

  /**
   * What was reported already, so that a task delivery does not log the same message again
   * and again: the version identifiers and tags which nobody could place, and the BPMN
   * processes whose BPMS keeps no list of versions at all.
   */
  private final java.util.Set<String> reportedAsUnknown = ConcurrentHashMap.newKeySet();

  /**
   * Where a finding goes, so that the whole start says it once.
   */
  private final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings;

  /**
   * Builds an empty registry. The {@link WorkflowTaskRegistry} keeps one of these, and a
   * test which only needs the version answers builds one of its own.
   * <p>
   * Nothing is known at this point. Everything in here arrives while the adapters wire their
   * BPMN. So the answers below are only worth asking for after the deployment.
   *
   * @param findings Where a finding of this registry goes
   */
  public ProcessVersions(
      final io.vanillabp.integration.adapter.migration.startup.StartupFindings findings) {

    this.findings = findings;

  }

  /**
   * Registers what one BPMS knows about one BPMN process. Registering the same catalog again
   * does not add it twice. That happens with a module deployed at every boot, or with
   * several workflow service classes.
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
   * Remembers that one BPMS keeps no list of versions for one BPMN process. That is a
   * statement, not the absence of one.
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
   * Whether one adapter reports the version of the process definition for that BPMN process.
   * It does if it registered a catalog, or if it said it keeps none and its deliveries carry
   * the version tag.
   * <p>
   * This answers "may an empty version still come?". An adapter which counts versions and
   * reports none for a workflow has a defect. One which counts none never will. An adapter
   * which said nothing either way answers <code>false</code> here. A reader who waited for a
   * version from it would wait forever.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @return Whether that adapter reports a version for that process
   */
  public boolean reportsVersions(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (adapterId == null) {
      return false;
    }
    final var key = new RegistryKey(workflowModuleId, bpmnProcessId);
    final var registered = catalogs.getOrDefault(key, List.of());
    if (registered
        .stream()
        .anyMatch(catalog -> adapterId.equals(catalog.adapterId()))) {
      return true;
    }
    return withoutACatalog
        .getOrDefault(key, List.of())
        .stream()
        .anyMatch(statement -> adapterId
            .equals(statement.adapterId()) && (statement.reported() == ReportedProcessVersion.VERSION_TAG));

  }

  /**
   * Whether one adapter keeps a catalog of the versions of that BPMN process. Then its BPMS
   * counts them, and can name the version of every workflow it runs.
   *
   * @param adapterId The adapter ID
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @return Whether that adapter registered a catalog for that process
   */
  public boolean keepsACatalog(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId) {

    return (adapterId != null) && catalogs
        .getOrDefault(new RegistryKey(workflowModuleId, bpmnProcessId), List.of())
        .stream()
        .anyMatch(catalog -> adapterId.equals(catalog.adapterId()));

  }

  /**
   * What the BPMS of that process said about their missing catalog, and only where NONE of
   * them registered one. A process which a second BPMS can be asked about is served by that
   * BPMS. So nothing is reported about the first.
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
   * What a delivery of the BPMS which serves that process can carry as its process version.
   * This is the most a method can hope for. So if two BPMS answer differently, the method
   * gets the wider answer.
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
   * Remembers the version which an adapter deployed during this boot (see
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#registerDeployedVersion}).
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
   * The line between the model this boot brought, and the older versions which the BPMS
   * holds.
   * <p>
   * A <code>null</code> answer has two meanings, and only the caller can tell them apart.
   * Where the module deployed the process, there is no older version to speak of. Where the
   * id was declared without a model, every version the BPMS holds under it is an older one
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
   * The order is the order in which the adapters registered. While two BPMS serve one
   * process during a migration, it decides the answer: the first one which knows a version
   * or tag wins. An empty answer means that nobody can be asked. That is not the same as "no
   * version is deployed".
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
   * The resolver asks the registered catalogs. It warns once per name about a version or
   * tag which none of them knows. So it belongs where an unknown name is worth a word. A
   * plain comparison of numbers needs no resolver at all.
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
   * Asks the catalogs without reporting anything. The startup check reports in its own
   * words, and names the method whose specification cannot be served.
   */
  private io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion lookup(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String versionOrVersionTag) {

    final var registered = catalogs.get(new RegistryKey(workflowModuleId, bpmnProcessId));
    if ((registered != null) && !registered.isEmpty()) {
      // asking a catalog may query the BPMS. A version which ANOTHER cluster node deployed
      // is unknown here until it is asked for (the catalogs cache the answer)
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
   * Loads all versions of the given BPMN process which the BPMS knows. It is called at
   * startup for the processes whose annotations name a version tag. So the tags are resolved
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
   * Reports a version tag which no BPMS knows, ONCE, and names what the developer can do
   * about it. It is not a boot failure. The tagged version may be deployed later, for example
   * in a rolling deployment where another cluster node is ahead. And an application whose
   * other methods serve the deployed versions has to keep running.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The plain BPMN process ID
   * @param versionTag The version tag named by an annotation
   * @param describedLocation The method which serves that specification, plus the
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
      // "no BPMS knows that tag" sounds like a typo. But on a BPMS which counts no versions
      // the tag is the one thing a delivery can carry. reportMethodsWhichNeverRun says in its
      // own words what such a process needs to hear
      return;
    }
    findings
        .warn(
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
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
      // the BPMS answered this while it was wired: there is no catalog to ask. Saying that
      // nobody can be asked would send the developer to look for an adapter which was never
      // missing
      findings
          .warn(
              io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
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
      // saying "no versions are deployed" here would be wrong. The BPMS may well hold
      // versions of this process. What is missing is an adapter which can ASK about them.
      // That is a different defect, with a different remedy
      findings
          .warn(
              io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
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
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
            "process '%s' of workflow module '%s'".formatted(bpmnProcessId, workflowModuleId),
            """
                Neither a deployed version nor a version tag '%s' of this BPMN process is known to \
                any BPMS - version specifications naming it match nothing until it is \
                deployed."""
                .formatted(versionOrVersionTag));

  }

  /**
   * Which methods of one BPMN process never run, because the BPMS which serves it keeps no
   * list of its versions. The {@link WorkflowTaskRegistry} answers it, because only it knows
   * the methods and their specifications.
   */
  @FunctionalInterface
  public interface MethodsWhichNeverRun {

    /**
     * Names the methods of that process which no delivery of such a BPMS can reach.
     * <p>
     * This is asked only where a BPMS said that it keeps no catalog. So the answer is a list
     * for one message and nothing else. An empty list keeps that message out altogether.
     *
     * @param workflowModuleId The workflow module ID
     * @param bpmnProcessId The plain BPMN process ID
     * @param reported What a delivery of that BPMS carries as its process version. That
     *          decides whether a specification can be met there at all
     * @return The methods, worded for the message. Empty where every method can be
     *         reached
     */
    List<String> of(
        String workflowModuleId,
        String bpmnProcessId,
        ReportedProcessVersion reported);

  }

  /**
   * Names the methods of one BPMN process which never run, because no BPMS which serves it
   * keeps a list of its versions. It does that ONCE per process, and says what would make
   * them run.
   * <p>
   * Only a BPMS which SAID so gets here. Where an adapter simply registered no catalog,
   * nothing is reported. For the core the two look the same, but they are not the same.
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#reportNoProcessVersionCatalog}
   * exists for that.
   * <p>
   * It is a warning and does not end the boot. A method named here can be the right one on
   * another BPMS the application runs on, and the same code then serves both. Decision 60
   * in the repository's DECISIONS.md says why the core warns and does not refuse.
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
            io.vanillabp.integration.spi.startup.StartupTopic.DEPLOYED_VERSIONS,
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
   * What a delivery of a BPMS without a list of versions carries. It is written for the
   * developer who reads either message about such a BPMS: the one at the start about the
   * methods which never run, and the one at a delivery which names a version nobody can
   * place. There is one wording, so that the two cannot start to contradict each other.
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
