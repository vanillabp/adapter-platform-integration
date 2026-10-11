package io.vanillabp.integration.adapter.migration.deployment;

import java.io.InputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.spi.parts.PartKind;
import io.vanillabp.integration.spi.parts.VanillaBpParts;
import io.vanillabp.integration.spi.startup.StartupTopic;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * Deploys the resources of the workflow modules to the BPMS, such as BPMN and DMN files.
 */
@Slf4j
public class DeploymentService {

  /**
   * The file extension of a BPMN file. These files bring the executable processes of a
   * workflow module.
   */
  public static final String BPMN_EXTENSION = ".bpmn";

  /**
   * The file extension of a DMN file. These are the decision tables which a business rule
   * task of the module calls. They are read AFTER the BPMN files, because they are added to
   * the processing context which reading a process produced.
   */
  public static final String DMN_EXTENSION = ".dmn";

  /**
   * @see DeploymentService#bpmsProcessingContexts
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process.
   */
  @Builder
  @Getter
  private static class ToBeStarted<PC> {
    private AdapterDeploymentService<?, PC> deploymentService;
    private PC bpmsProcessingContext;
  }

  @Getter
  @Setter
  private static class BpmsProcessingContextHolder<PC> {
    private PC bpmsProcessingContext;
  }

  /**
   * VanillaBP properties.
   */
  private final MigrationAdapterProperties properties;

  /**
   * Deployment services of all adapters.
   */
  private final List<AdapterDeploymentService<?, ?>> deploymentServices;

  /**
   * Wiring services of extensions only. {@link AdapterDeploymentService} extends
   * {@link ExtensionWiringService}. So adapters may show up in the list of wiring services
   * passed to the constructor, for example when a bean container collects them by type.
   * They are filtered out, because the deployment pipeline wires adapters itself.
   */
  private final List<ExtensionWiringService<?, ?>> wiringServices;

  /**
   * The processing contexts to start after the deployment, when the application has booted,
   * per workflow module id. There is one entry for each adapter the workflow module was
   * deployed to. Those are the union of the prioritized adapters of the module and of all its
   * workflows. During a BPMS migration all deployed adapters have to keep processing
   * workflows.
   */
  private final Map<String, List<ToBeStarted<?>>> bpmsProcessingContexts;

  /**
   * The core's own wiring interface. It is used for what belongs to a whole workflow module
   * and what no adapter has to remember (see {@link #runModuleLevelChecks(String)}). It may
   * be <code>null</code> in tests which only exercise the pipeline itself.
   */
  private final io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring workflowTaskWiring;

  /**
   * What the platform integration can add to the report about a BPMN process which nothing
   * claims. It is absent where the platform has nothing to say. A boot which reports no such
   * process never asks it.
   */
  private final UnclaimedBpmnProcessHints unclaimedProcessHints;

  /**
   * The pipeline without the checks which belong to a whole workflow module.
   *
   * @param properties Attributes for configuration of the deployment process.
   * @param deploymentServices All adapters deployment services.
   * @param wiringServices The extensions to be wired. Adapters may show up in this list as
   *          well and are filtered out, because the pipeline wires them itself.
   */
  public DeploymentService(
      final MigrationAdapterProperties properties,
      final List<AdapterDeploymentService<?, ?>> deploymentServices,
      final List<ExtensionWiringService<?, ?>> wiringServices) {

    this(properties, deploymentServices, wiringServices, null);

  }

  /**
   * The pipeline with the module-level checks, but without a hint of the platform.
   *
   * @param properties Attributes for configuration of the deployment process.
   * @param deploymentServices All adapters deployment services.
   * @param wiringServices The extensions to be wired
   * @param workflowTaskWiring The core's wiring interface. The checks about a whole workflow
   *          module run through it. <code>null</code> leaves them out
   */
  public DeploymentService(
      final MigrationAdapterProperties properties,
      final List<AdapterDeploymentService<?, ?>> deploymentServices,
      final List<ExtensionWiringService<?, ?>> wiringServices,
      final io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring workflowTaskWiring) {

    this(properties, deploymentServices, wiringServices, workflowTaskWiring, null);

  }

  /**
   * The full pipeline, which is what a platform integration builds.
   *
   * @param properties Attributes for configuration of the deployment process.
   * @param deploymentServices All adapters deployment services.
   * @param wiringServices The extensions to be wired
   * @param workflowTaskWiring The core's wiring interface
   * @param unclaimedProcessHints What the platform can add to the report about a BPMN process
   *          which nothing claims. <code>null</code> where it has nothing to say. It is never
   *          asked on a boot without such a process
   */
  public DeploymentService(
      final MigrationAdapterProperties properties,
      final List<AdapterDeploymentService<?, ?>> deploymentServices,
      final List<ExtensionWiringService<?, ?>> wiringServices,
      final io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring workflowTaskWiring,
      final UnclaimedBpmnProcessHints unclaimedProcessHints) {

    this.workflowTaskWiring = workflowTaskWiring;
    this.unclaimedProcessHints = unclaimedProcessHints;
    this.properties = properties;
    this.deploymentServices = deploymentServices;
    this.wiringServices = new LinkedList<>(wiringServices
        .stream()
        // the deployment pipeline wires adapters itself (see the comment of the field)
        .filter(wiringService -> !(wiringService instanceof AdapterDeploymentService))
        .toList());
    this.wiringServices.sort(Comparator.comparingInt(ExtensionWiringService::getOrder));
    bpmsProcessingContexts = new HashMap<>();

  }

  /**
   * Deploys all resources of all workflow modules to the BPMS. The steps are:
   * <ol>
   *   <li>for each adapter the workflow module has to be deployed to (the union of the
   *       prioritized adapters of the module and of all its workflows)</li>
   *   <li>read the BPMN and DMN files</li>
   *   <li>let the adapter prepare the BPMN (set default behavior and so on)</li>
   *   <li>for each adapter and extension with the same model type and process context type
   *       (for example all of them for Camunda 8)
   *     <ol>
   *       <li>wire the business code to the BPMN's tasks</li>
   *     </ol>
   *   </li>
   *   <li>deploy the result to the BPMS</li>
   * </ol>
   * <p>
   * A workflow module is deployed to the UNION of two sets of adapters: the module's
   * effective list of prioritized adapters, and every adapter named in a workflow-level
   * <code>prioritized-adapters</code> override of that module
   * ({@link MigrationAdapterProperties#getDeploymentAdaptersFor(String)}). The BPMS election
   * works per process, but the deployment works per file. So an adapter which is prioritized
   * for one single workflow still has to receive the module's resources. Otherwise starting
   * that workflow would fail at runtime. Every adapter of the union receives ALL resources of
   * the module. Filtering per process was considered and rejected. A BPMN file may contain
   * several processes, and adapters deploy whole files. Extra processes deployed to an
   * adapter do nothing, because the election routes the workflow starts. During a BPMS
   * migration it is even good to have the complete model of the module in both BPMS.
   * <p>
   * A failing deployment stops the boot of the application, with one exception. The
   * property <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code> is set to
   * <code>warn</code> for the failing adapter, <i>and</i> the adapter is not the
   * first-priority adapter of the workflow module or of any of its workflows. Then the
   * failure goes into the block of the start, and the application still starts. An example
   * is the old BPMS of a migration which cannot be reached for a while. If the failing
   * adapter has first priority for the module or for a single workflow, the boot always
   * fails. Otherwise new workflows could not be started.
   * <p>
   * After all adapters were processed, a WARN reports the configured workflow IDs
   * (<code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;bpmnProcessId&gt;</code>)
   * which match no executable BPMN process in the module's resources. It names the known
   * BPMN process IDs. It is not a failure, because the BPMN may arrive later, for example
   * during a migration.
   * <p>
   * An executable BPMN process which no <code>&#64;WorkflowService</code> class claims ends
   * the start. The exception is a process which the application marks as somebody else's
   * (see {@link #refuseProcessesNobodyClaims(Map)}). Once such a process was found, nothing
   * is deployed.
   *
   * @param workflowModuleIds The workflow module IDs to deploy
   * @param resourcesLoader Loads the resources of one location. It gets the location and a
   *     file extension ({@value #BPMN_EXTENSION} or {@value #DMN_EXTENSION}). It returns a
   *     map with the filename as the key and the file's input stream as the value.
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process.
   */
  public <PC> void deployResources(
      final List<String> workflowModuleIds,
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader) {

    try {
      deployResourcesOfEachModule(workflowModuleIds, resourcesLoader);
    } catch (final RuntimeException e) {
      // this start ends here. So the box is written now, and what was found stands next to
      // the exception which the developer is about to read
      properties
          .startupFindings()
          .sayWhatWasFoundBeforeTheStartFailed();
      throw e;
    }

  }

  /**
   * The deployment itself, of every workflow module, as
   * {@link #deployResources(List, BiFunction)} describes it.
   *
   * @param workflowModuleIds The workflow module IDs to deploy
   * @param resourcesLoader Loads the resources of one location
   * @param <PC> The processing context of the adapter being deployed to
   */
  @SuppressWarnings("unchecked")
  private <PC> void deployResourcesOfEachModule(
      final List<String> workflowModuleIds,
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader) {

    endTheStartIfSomethingWasRefused();

    // artifacts of several release cycles meet in one application. A pair which was never
    // built together has to be found before anything runs
    checkPartsBelongTogether();

    // several ids of one adapter type only make sense if they address DIFFERENT systems,
    // and the ADAPTER decides that
    validateDistinctAdapterInstances();

    // which file each executable BPMN process came from, per workflow module. The reports
    // after the deployment name it, so that a developer can open the right file
    final var bpmnFilesByProcessId = new HashMap<String, Map<String, String>>();
    // the processes which nobody claims and nobody marked as somebody else's, per workflow
    // module, with their files. Each of them ends the start, and once one is found nothing
    // is deployed
    final var processesNobodyClaims = new LinkedHashMap<String, Map<String, String>>();

    workflowModuleIds
        .stream()
        // for each adapter the module has to be deployed to (the union of the prioritized
        // adapters of the module and of all its workflows)...
        .flatMap(workflowModuleId -> properties
            .getDeploymentAdaptersFor(workflowModuleId)
            .stream()
            // ...find the right deployment service...
            .map(adapterId -> deploymentServices
                .stream()
                .filter(service -> service.getAdapterId().equals(adapterId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "No deployment service found for adapter '%s'!".formatted(adapterId)
                )))
            .map(deploymentService -> Map.entry(
                workflowModuleId,
                (AdapterDeploymentService<?, PC>) deploymentService)))
        // and process the resources of the workflow module for that adapter
        .forEach(deploymentServiceEntry -> {
          final var workflowModuleId = deploymentServiceEntry.getKey();
          final var deploymentService = deploymentServiceEntry.getValue();
          try {
            deployResourcesOfAdapter(
                workflowModuleId,
                deploymentService,
                resourcesLoader,
                bpmnFilesByProcessId.computeIfAbsent(workflowModuleId, id -> new LinkedHashMap<>()),
                processesNobodyClaims);
          } catch (final RuntimeException e) {
            final var adapterId = deploymentService.getAdapterId();
            final var policy = properties.getDeploymentFailureFor(adapterId);
            if ((policy == DeploymentFailurePolicy.WARN) && !properties.isFirstPriorityFor(workflowModuleId,
                adapterId)) {
              properties
                  .startupFindings()
                  .warn(
                      StartupTopic.INFRASTRUCTURE,
                      "workflow module '%s', adapter '%s'".formatted(workflowModuleId, adapterId),
                      """
                          Deployment of workflow module '%s' failed for adapter '%s'! Since \
                          '%s.adapters.%s.deployment-failure' is set to 'warn' and the adapter is \
                          not the first-priority adapter of the workflow module or of any of its \
                          workflows, the application starts anyway. Workflows of this workflow \
                          module are not processed by adapter '%s'! What went wrong:
                          %s"""
                          .formatted(
                              workflowModuleId,
                              adapterId,
                              MigrationAdapterProperties.PREFIX,
                              adapterId,
                              adapterId,
                              whatWentWrong(e)));
            } else {
              throw e;
            }
          }
        });

    // a process which nobody claims ends the start before anything else is said about the
    // deployment, because once it was found nothing was deployed
    refuseProcessesNobodyClaims(processesNobodyClaims);
    endTheStartIfSomethingWasRefused();

    reportWorkflowModulesWithoutResources(workflowModuleIds);

    warnAboutConfiguredWorkflowsUnknownToBpmnResources(workflowModuleIds, bpmnFilesByProcessId);

    workflowModuleIds.forEach(this::runModuleLevelChecks);

  }

  /**
   * What belongs to a workflow module as a whole. It runs once every adapter of the module
   * has deployed. It covers the versions which the BPMS still holds for a BPMN process that
   * the application only DECLARES, and the two checks which judge the module.
   * <p>
   * These used to be the adapter's duty. Only the javadoc of the SPI said so, and Camunda 7
   * forgot one of them for a year. A typo in a
   * <code>&#64;WorkflowTask(taskDefinition = ...)</code> stayed silent until a workflow
   * reached the task. Neither check needs anything which an adapter knows. They need the
   * moment when the module is deployed, and that is now. So the core takes the duty instead
   * of asking every adapter author to remember it.
   * <p>
   * {@code WorkflowTaskWiring#registerDeployedVersion} stays with the adapter. Only the
   * adapter knows which version its BPMS ended up with.
   * <p>
   * Two reports run here as well. Both judge nothing, and both need the moment when
   * everything they name is known. One says what the handler methods of the extensions were
   * wired to. The other says which <code>&#64;WorkflowTask</code> methods would be called
   * with missing values where VanillaBP works out a cancellation itself.
   *
   * @param workflowModuleId The workflow module which finished deploying
   */
  private void runModuleLevelChecks(
      final String workflowModuleId) {

    if (workflowTaskWiring == null) {
      return;
    }
    // the versions of an id which is only declared come first. The reverse wiring check
    // needs them to tell a method kept for a renamed process from one wired to nothing
    askAdaptersAboutProcessesNobodyDeployed(workflowModuleId);
    // then the reverse wiring check. A method which serves no task at all is a more basic
    // defect than two methods which serve the same task in overlapping version ranges. The
    // version resolution ends the boot over that once the tags are placed
    workflowTaskWiring.validateNoUnwiredWorkflowTaskMethods(workflowModuleId);
    workflowTaskWiring.resolveProcessVersions(workflowModuleId);
    // then what a cancellation which VanillaBP works out itself cannot carry. This needs
    // the versions placed, because the methods it names are the ones which really run
    workflowTaskWiring.reportWhatACancelationCannotCarry(workflowModuleId);
    // and last what judges nothing: which method of an extension serves which key of which
    // process. A developer whose method is not called has something to read then
    workflowTaskWiring.reportExtensionHandlerWiring(workflowModuleId);

  }

  /**
   * Asks every adapter of the workflow module what its BPMS holds for the BPMN processes
   * which the application DECLARED without deploying anything under them. An example is the
   * id which a renamed BPMN process left behind, while the BPMS still holds it with running
   * workflows.
   * <p>
   * The two ends of this could not meet before. An adapter registers the versions of the
   * processes it just deployed. It cannot know which further ids an application declared.
   * The core knows the declarations, but it cannot ask a BPMS. So the core asks here, where
   * the module is deployed and every id it brought is known.
   *
   * @param workflowModuleId The workflow module which finished deploying
   */
  private void askAdaptersAboutProcessesNobodyDeployed(
      final String workflowModuleId) {

    properties
        .getDeploymentAdaptersFor(workflowModuleId)
        .forEach(adapterId -> deploymentServices
            .stream()
            .filter(deploymentService -> deploymentService.getAdapterId().equals(adapterId))
            .findFirst()
            .ifPresent(deploymentService -> workflowTaskWiring
                .registerVersionsOfProcessesNobodyDeployed(
                    workflowModuleId,
                    adapterId,
                    deploymentService::processVersionCatalogOf)));

  }

  /**
   * Reports a workflow module for which NO adapter found resources. Each adapter already
   * warned about its own location. But a module which none of them could serve needs a
   * different message. Nothing of this module runs. The failure which the developer meets
   * later comes from the BPMS ("no processes deployed with key ..."), and it names neither
   * VanillaBP nor a location.
   * <p>
   * The boot goes on on purpose. BPMN may arrive with an adapter deployed later, which is
   * the migration case this platform exists for. And a module without resources breaks
   * nothing by itself.
   *
   * @param workflowModuleIds The workflow module IDs which were deployed
   */
  private void reportWorkflowModulesWithoutResources(
      final List<String> workflowModuleIds) {

    workflowModuleIds
        .stream()
        .filter(workflowModuleId -> !bpmsProcessingContexts.containsKey(workflowModuleId))
        .forEach(workflowModuleId -> properties
            .startupFindings()
            .error(
                StartupTopic.BPMN_MODELS,
                "workflow module '%s'".formatted(workflowModuleId),
                """
                    No BPMN resources were found for workflow module '%s' - by NONE of its \
                    adapters (%s). No workflow of this module can be started; the BPMS would \
                    report an unknown process. Locations searched: %s. Check where the module's \
                    BPMN files are packaged, or name the location explicitly in \
                    '%s.workflow-modules.%s.adapters.<adapter>.resources-location'.%s"""
                    .formatted(
                        workflowModuleId,
                        String.join(", ", properties.getDeploymentAdaptersFor(workflowModuleId)),
                        properties
                            .getDeploymentAdaptersFor(workflowModuleId)
                            .stream()
                            .flatMap(adapterId -> properties
                                .getAdapterResourcesLocationsFor(workflowModuleId, adapterId)
                                .stream())
                            .map(location -> "'%s'".formatted(location.location()))
                            .distinct()
                            .collect(java.util.stream.Collectors.joining(", ")),
                        MigrationAdapterProperties.PREFIX,
                        workflowModuleId,
                        properties
                            .whyTheConventionSearchedThere(workflowModuleId)
                            .map(" "::concat)
                            .orElse(""))));

  }

  /**
   * Asks each adapter type whose ids are configured more than once whether those ids really
   * address DIFFERENT systems
   * ({@link AdapterDeploymentService#validateDistinctAdapterInstances(List)}). Two instances
   * of one BPMS with the same database, endpoint or credentials are the same instance.
   * Configuring them as separate adapters is a defect, and the adapter reports it with a
   * guiding message.
   */
  private void validateDistinctAdapterInstances() {

    final var deploymentServicesByType = new LinkedHashMap<String, List<AdapterDeploymentService<?, ?>>>();
    deploymentServices
        .forEach(deploymentService -> deploymentServicesByType
            .computeIfAbsent(deploymentService.getAdapterType(), type -> new LinkedList<>())
            .add(deploymentService));

    deploymentServicesByType.forEach((
        adapterType,
        servicesOfType) -> {
      if (servicesOfType.size() < 2) {
        return;
      }
      // in the order of their priority, because the adapter's message may name them
      final var prioritizedIdsOfType = properties
          .getPrioritizedAdapters()
          .stream()
          .filter(adapterId -> servicesOfType
              .stream()
              .anyMatch(service -> service.getAdapterId().equals(adapterId)))
          .toList();
      servicesOfType
          .getFirst()
          .validateDistinctAdapterInstances(
              prioritizedIdsOfType.size() == servicesOfType.size()
                  ? prioritizedIdsOfType
                  : servicesOfType
                      .stream()
                      .map(AdapterDeploymentService::getAdapterId)
                      .toList());
    });

  }

  /**
   * Judges every adapter and every extension against the platform integration they run on.
   * It ends the boot of an application whose parts do not belong together.
   * {@link VanillaBpParts} has the rule and the messages, and an adapter calls it for itself
   * too. Decision 71 in the repository's DECISIONS.md says what the rule is and why.
   * <p>
   * Every part is judged before the first one is reported. So a developer who got two
   * things wrong learns both in one boot, not one per restart.
   * <p>
   * An extension is only judged if it names itself
   * ({@link ExtensionWiringService#getExtensionName()}). An extension which does not is left
   * alone. The boot could only name a class, and the application developer who reads the
   * log cannot do anything with that.
   * <p>
   * The refusal is thrown here and not collected with the others. A part built against
   * another platform integration is the one failure which makes every later check
   * unreliable. The next call into that part may end in a <code>NoSuchMethodError</code>.
   * And a box full of findings from a classpath which does not hold together would send the
   * developer after the wrong thing. The warnings found here do go into the box. They say
   * nothing about whether the rest can be trusted.
   */
  private void checkPartsBelongTogether() {

    final var reasonsNotToBoot = new LinkedList<String>();

    Stream
        .concat(
            deploymentServices
                .stream()
                .map(adapter -> VanillaBpParts.judge(
                    PartKind.ADAPTER,
                    adapter.getAdapterType(),
                    adapter.getClass())),
            wiringServices
                .stream()
                .filter(extension -> extension.getExtensionName() != null)
                .map(extension -> VanillaBpParts.judge(
                    PartKind.EXTENSION,
                    extension.getExtensionName(),
                    extension.getClass())))
        .flatMap(Optional::stream)
        .forEach(verdict -> {
          if (verdict.stopsTheBoot()) {
            reasonsNotToBoot.add(verdict.message());
          } else {
            properties
                .startupFindings()
                .warn(
                    StartupTopic.PARTS_AND_VERSIONS,
                    null,
                    verdict.message());
          }
        });

    if (!reasonsNotToBoot.isEmpty()) {
      throw new IllegalStateException(String.join(System.lineSeparator() + System.lineSeparator(),
          reasonsNotToBoot));
    }

  }

  /**
   * Reports configured workflow IDs
   * (<code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;bpmnProcessId&gt;</code>)
   * which match no executable BPMN process in the module's resources. It is only a warning,
   * like for configured workflow modules which are missing in the classpath. The BPMN may
   * arrive later, for example during a BPMS migration, so the boot must not be stopped. It
   * runs after {@link #deployResources(List, BiFunction)} processed all adapters, because
   * the BPMN process IDs are known only after the adapters' <code>readBpmn</code>. IDs found
   * by ANY adapter count as known, because the resources location may differ per
   * adapter.
   *
   * @param workflowModuleIds The workflow module IDs deployed
   * @param bpmnFilesByProcessId The BPMN file each executable process was found in,
   *          per workflow module
   */
  private void warnAboutConfiguredWorkflowsUnknownToBpmnResources(
      final List<String> workflowModuleIds,
      final Map<String, Map<String, String>> bpmnFilesByProcessId) {

    workflowModuleIds.forEach(workflowModuleId -> {
      final var workflowModule = properties.getWorkflowModules().get(workflowModuleId);
      if ((workflowModule == null) || workflowModule.getWorkflows().isEmpty()) {
        return;
      }
      final var knownProcessIds = bpmnFilesByProcessId.getOrDefault(workflowModuleId, Map.of()).keySet();
      final var unknownConfiguredWorkflows = workflowModule
          .getWorkflows()
          .keySet()
          .stream()
          .filter(bpmnProcessId -> !knownProcessIds.contains(bpmnProcessId))
          .sorted()
          .toList();
      if (unknownConfiguredWorkflows.isEmpty()) {
        return;
      }
      final var propPrefix = "\n  %s.workflow-modules.%s.workflows.".formatted(
          MigrationAdapterProperties.PREFIX,
          workflowModuleId);
      properties
          .startupFindings()
          .warn(
              StartupTopic.CONFIGURATION,
              "workflow module '%s'".formatted(workflowModuleId),
              """
                  Found properties for BPMN processes
                    %s.workflow-modules.%s.workflows.%s
                  which were not found in any BPMN resource of workflow module '%s'! Fix the BPMN process ID
                  or add the BPMN file. Two cases are intended: the BPMN arrives later (e.g. during a BPMS
                  migration), and the id belongs to a BPMN process this application declares without
                  deploying one - a renamed process, whose old id keeps the versions of it a BPMS still
                  holds and which 'outfaded-versions' is configured for right here. Executable BPMN process
                  IDs known for this workflow module are: %s."""
                  .formatted(
                      MigrationAdapterProperties.PREFIX,
                      workflowModuleId,
                      String.join(propPrefix, unknownConfiguredWorkflows),
                      workflowModuleId,
                      knownProcessIds.isEmpty()
                          ? "none"
                          : "'%s'".formatted(
                              String.join("', '", knownProcessIds.stream().sorted().toList()))));
    });

  }

  /**
   * Ends the start because of the executable BPMN processes of a workflow module which no
   * <code>&#64;WorkflowService</code> class claims, and which the application did not mark as
   * somebody else's. A BPMN file is deployed to the BPMS as a whole. So a process drawn next
   * to the one the application asked for travels with it. The start cannot tell such a
   * process from a forgotten workflow service. Only the application can, so it has to say
   * which it is. For a process it serves, it writes a workflow service. For a process which
   * something else serves, it writes the line
   * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;process&gt;.implemented-externally=true</code>.
   * A marked process is deployed with its file and otherwise left alone. No adapter wires
   * it, and no check ends the start because of it. See decision 120 in the repository's
   * DECISIONS.md.
   *
   * @param processesNobodyClaims The processes which nobody claims and nobody marked, with
   *          their files, per workflow module
   */
  private void refuseProcessesNobodyClaims(
      final Map<String, Map<String, String>> processesNobodyClaims) {

    processesNobodyClaims.forEach((
        workflowModuleId,
        filesByProcessId) -> {
      if (filesByProcessId.isEmpty()) {
        return;
      }
      final var processIds = filesByProcessId
          .keySet()
          .stream()
          .sorted()
          .toList();
      properties
          .startupFindings()
          .refuse(
              StartupTopic.CODE,
              "workflow module '%s'".formatted(workflowModuleId),
              """
                  Workflow module '%s' deploys BPMN processes which no @WorkflowService class of this \
                  application claims:%s
                  A BPMN file goes to the BPMS as a whole, so a process modelled next to the one you \
                  asked for goes with it. Say for each of them who serves it. If this application \
                  does, add a class annotated with \
                  @WorkflowService(bpmnProcess = @BpmnProcess(bpmnProcessId = "<the process>")) holding \
                  a @WorkflowTask method per task. If something else does, add this line, and VanillaBP \
                  deploys the process with its file and leaves it alone otherwise:%s
                  Or take the process out of its file.%s"""
                  .formatted(
                      workflowModuleId,
                      processIds
                          .stream()
                          .map(bpmnProcessId -> "\n  - process '%s' of file '%s'".formatted(
                              bpmnProcessId,
                              filesByProcessId.get(bpmnProcessId)))
                          .collect(java.util.stream.Collectors.joining()),
                      processIds
                          .stream()
                          .map(bpmnProcessId -> io.vanillabp.integration.adapter.spi.workflowtask.ImplementedExternally
                              .processPropertyLine(workflowModuleId, bpmnProcessId)
                              .indent(2)
                              .stripTrailing())
                          .collect(java.util.stream.Collectors.joining("\n", "\n", "")),
                      whatElseIsWorthSaying(workflowModuleId, processIds)));
    });

  }

  /**
   * Whether a BPMN process found in a file of the workflow module ends the start. That is so
   * when nobody claims it, and the application did not mark it as somebody else's for this
   * adapter (see {@link #refuseProcessesNobodyClaims(Map)}).
   *
   * @param workflowModuleId The workflow module ID
   * @param adapterId The adapter deploying the file
   * @param bpmnProcessId The BPMN process ID
   * @return Whether nobody claims the process and nothing says that it belongs to somebody else
   */
  private boolean nobodyClaims(
      final String workflowModuleId,
      final String adapterId,
      final String bpmnProcessId) {

    if (workflowTaskWiring == null) {
      // tests which exercise only the pipeline run it without the core's wiring interface,
      // and then there is nobody to ask
      return false;
    }
    if (workflowTaskWiring.isClaimedByAWorkflowService(workflowModuleId, bpmnProcessId)) {
      return false;
    }
    return !Boolean.TRUE.equals(properties.implementedExternallyAtTheWorkflow(
        workflowModuleId,
        bpmnProcessId,
        adapterId));

  }

  /**
   * What the platform integration has to add about the processes just refused. The lines
   * become part of the same finding. So a reader gets one report per workflow module, not
   * two which have to be read together.
   * <p>
   * It is asked here and nowhere else. So the cost of the answer is paid on a boot which
   * already reports a problem, and never on a healthy one.
   *
   * @param workflowModuleId The workflow module being reported
   * @param unclaimedProcessIds The BPMN process ids nothing claims
   * @return The lines to append, empty where the platform has nothing to say
   */
  private String whatElseIsWorthSaying(
      final String workflowModuleId,
      final Collection<String> unclaimedProcessIds) {

    if (unclaimedProcessHints == null) {
      return "";
    }
    final List<String> hints;
    try {
      hints = unclaimedProcessHints.whatElseIsWorthSaying(workflowModuleId, unclaimedProcessIds);
    } catch (final RuntimeException e) {
      // a hint which fails would replace the refusal it belongs to with an exception which
      // names neither the processes nor the way out
      log.debug("Could not look for further reasons why nothing claims a process", e);
      return "";
    }
    if ((hints == null) || hints.isEmpty()) {
      return "";
    }
    return hints
        .stream()
        .collect(java.util.stream.Collectors.joining("\n", "\n", ""));

  }

  /**
   * Deploys the resources of the given workflow module with the given adapter's deployment
   * service. It remembers the resulting processing context for
   * {@link #startWorkflowProcessing(List)}.
   *
   * @param workflowModuleId The workflow module ID
   * @param deploymentService The deployment service to use
   * @param resourcesLoader Loads the files of one location and extension
   * @param bpmnFilesByProcessId Collects the file in which each executable BPMN process was
   *     found. The reports after all adapters were processed use it
   * @param processesNobodyClaims Collects the processes which nobody claims and nobody
   *     marked, per workflow module. Once it holds one, nothing is deployed any more
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process.
   */
  private <PC> void deployResourcesOfAdapter(
      final String workflowModuleId,
      final AdapterDeploymentService<?, PC> deploymentService,
      final BiFunction<String, String, Map<String, InputStream>> resourcesLoader,
      final Map<String, String> bpmnFilesByProcessId,
      final Map<String, Map<String, String>> processesNobodyClaims) {

    // a configured location is the only one. The convention may name two locations: the
    // application IS the workflow module, and so is a module tested inside its own Maven
    // module, which keeps its files below the module ID. The first location with files
    // wins, never both, so a process cannot be deployed twice.
    final var candidateLocations = properties.getAdapterResourcesLocationsFor(
        workflowModuleId,
        deploymentService.getAdapterId());
    var searchedLocation = candidateLocations.getFirst();
    var foundFiles = Map.<String, InputStream>of();
    for (final var candidate : candidateLocations) {
      final var filesOfCandidate = resourcesLoader.apply(candidate.location(), BPMN_EXTENSION);
      if (!filesOfCandidate.isEmpty()) {
        searchedLocation = candidate;
        foundFiles = filesOfCandidate;
        break;
      }
    }
    final var resourcesLocation = searchedLocation;
    final var bpmsProcessingContext = new BpmsProcessingContextHolder<PC>();
    final var bpmnFiles = foundFiles;
    try {
      bpmnFiles
          .entrySet()
          .forEach(bpmnFileEntry -> processBpmn(
              workflowModuleId,
              deploymentService,
              bpmsProcessingContext.getBpmsProcessingContext(),
              bpmnFileEntry.getKey(), // filename
              bpmnFileEntry.getValue(), // InputStream
              resourcesLocation.vanillaBpBpmn(),
              bpmnFilesByProcessId,
              processesNobodyClaims.computeIfAbsent(workflowModuleId, id -> new java.util.TreeMap<>()))
              .ifPresentOrElse(
                  bpmsProcessingContext::setBpmsProcessingContext,
                  () -> properties
                      .startupFindings()
                      .warn(
                          StartupTopic.BPMN_MODELS,
                          "file '%s' of workflow module '%s'".formatted(
                              bpmnFileEntry.getKey(),
                              workflowModuleId),
                          "This BPMN file contains no executable process, so it was not deployed. "
                              + "Mark the process executable in the model, or take the file out of "
                              + "the workflow module.")));
    } finally {
      // the platform's resources loader opens the streams, and this pipeline owns them. So
      // close ALL of them whatever the outcome, also the ones never processed because an
      // earlier file failed. Adapters must not close them, see
      // AdapterDeploymentService#readBpmn
      bpmnFiles.forEach((
          filename,
          bpmn) -> {
        try {
          bpmn.close();
        } catch (final java.io.IOException e) {
          log.warn(
              "Could not close the stream of BPMN file '{}' of workflow module '{}'",
              filename,
              workflowModuleId,
              e);
        }
      });
    }

    // no executable processes (an empty or missing location, or only non-executable BPMN):
    // warn with the key to change, and skip this adapter for this module. The adapter must
    // never be called with a null processing context
    if (bpmsProcessingContext.getBpmsProcessingContext() == null) {
      properties
          .startupFindings()
          .warn(
              StartupTopic.BPMN_MODELS,
              "workflow module '%s', adapter '%s'".formatted(
                  workflowModuleId,
                  deploymentService.getAdapterId()),
              """
                  No executable BPMN processes found for workflow module '%s' at location %s! \
                  Adapter '%s' is skipped for this workflow module, so none of its workflows can be \
                  started by that adapter, and any DMN file at that location is not deployed either \
                  (a decision table travels with the processes calling it, never on its own). If \
                  this is unintended, check property \
                  '%s.workflow-modules.%s.adapters.%s.resources-location' (or '%s.resources-location') \
                  and the BPMN files at that location."""
                  .formatted(
                      workflowModuleId,
                      candidateLocations.size() == 1
                          ? "'%s'".formatted(resourcesLocation.location())
                          : candidateLocations
                              .stream()
                              .map(candidate -> "'%s'".formatted(candidate.location()))
                              .collect(java.util.stream.Collectors.joining(" and ")),
                      deploymentService.getAdapterId(),
                      MigrationAdapterProperties.PREFIX,
                      workflowModuleId,
                      deploymentService.getAdapterId(),
                      MigrationAdapterProperties.PREFIX));
      return;
    }

    // the decision tables of the module go into the context which its processes produced. A
    // business rule task calls a decision of its own module, so both travel together
    processDmnFiles(
        workflowModuleId,
        deploymentService,
        resourcesLoader.apply(resourcesLocation.location(), DMN_EXTENSION),
        bpmsProcessingContext);

    // a process which nobody claims ends this start, so nothing goes out to a BPMS any more.
    // An application which is about to end must not change a system outside of it
    if (processesNobodyClaims
        .values()
        .stream()
        .anyMatch(processes -> !processes.isEmpty())) {
      return;
    }

    // ...and finally deploy all resources together (BPMN, DMN) to the BPMS
    deploymentService.deployResources(workflowModuleId, bpmsProcessingContext.getBpmsProcessingContext());
    bpmsProcessingContexts
        .computeIfAbsent(workflowModuleId, id -> new LinkedList<>())
        .add(ToBeStarted
            .<PC>builder()
            .deploymentService(deploymentService)
            .bpmsProcessingContext(bpmsProcessingContext.getBpmsProcessingContext())
            .build());

  }

  /**
   * Hands every DMN file of the workflow module to the adapter. The adapter adds it to the
   * processing context, so that {@link AdapterDeploymentService#deployResources} deploys it
   * together with the module's processes.
   * <p>
   * This pipeline owns the streams, as it owns the BPMN ones. They are closed here, whatever
   * the adapter did with them. That includes the ones after a failing file.
   *
   * @param workflowModuleId The workflow module ID
   * @param deploymentService The adapter's deployment service
   * @param dmnFiles The DMN files found at the module's resources location for this adapter
   * @param bpmsProcessingContext The context the module's BPMN files produced
   * @param <PC> The processing context
   */
  private <PC> void processDmnFiles(
      final String workflowModuleId,
      final AdapterDeploymentService<?, PC> deploymentService,
      final Map<String, InputStream> dmnFiles,
      final BpmsProcessingContextHolder<PC> bpmsProcessingContext) {

    try {
      dmnFiles
          .forEach((
              filename,
              dmn) -> {
            log.debug(
                "Reading DMN file '{}' of workflow module '{}' for adapter '{}'",
                filename,
                workflowModuleId,
                deploymentService.getAdapterId());
            bpmsProcessingContext
                .setBpmsProcessingContext(
                    deploymentService
                        .readDmn(
                            workflowModuleId,
                            bpmsProcessingContext.getBpmsProcessingContext(),
                            filename,
                            dmn));
          });
    } finally {
      dmnFiles.forEach((
          filename,
          dmn) -> {
        try {
          dmn.close();
        } catch (final java.io.IOException e) {
          log.warn(
              "Could not close the stream of DMN file '{}' of workflow module '{}'",
              filename,
              workflowModuleId,
              e);
        }
      });
    }

  }

  /**
   * Processes the given BPMN file.
   *
   * @param workflowModuleId The workflow module ID
   * @param deploymentService The deployment service to use
   * @param bpmsProcessingContext The context used to store all information needed by the adapter to deploy the process
   * @param filename The filename of the BPMN file (used for logging and error messages)
   * @param bpmn The BPMN resource inputstream
   * @param isVanillaBpBpmn Whether the BPMN is VanillaBP's BPMN or is specific to the adapter's BPMS
   * @param bpmnFilesByProcessId Collects the file each executable BPMN process was found in
   * @param processesNobodyClaims Collects the processes of the file nobody claims and nobody
   *          marked as somebody else's, with the file
   * @param <BPMN> The BPMN model type
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process
   * @return The context used to store all information needed by the adapter to deploy the process
   */
  @SuppressWarnings("unchecked")
  private <BPMN, PC> Optional<PC> processBpmn(
      final String workflowModuleId,
      final AdapterDeploymentService<BPMN, PC> deploymentService,
      final PC bpmsProcessingContext,
      final String filename,
      final InputStream bpmn,
      final boolean isVanillaBpBpmn,
      final Map<String, String> bpmnFilesByProcessId,
      final Map<String, String> processesNobodyClaims) {

    final var executableProcesses = deploymentService.readBpmn(
        workflowModuleId,
        filename,
        bpmn,
        isVanillaBpBpmn);
    if (executableProcesses.isEmpty()) {
      return Optional.empty();
    }

    // process each executable process, and pass the processing context collected so far
    // through ALL processes of the file...
    var context = bpmsProcessingContext;
    for (final var processIdAndModel : executableProcesses) {
      final var bpmnProcessId = processIdAndModel.getKey();
      final var bpmnModel = processIdAndModel.getValue();
      // the first adapter which finds the process names the file. Two adapters may read the
      // same module from locations of their own, and the message wants one name
      bpmnFilesByProcessId.putIfAbsent(bpmnProcessId, filename);
      if (nobodyClaims(workflowModuleId, deploymentService.getAdapterId(), bpmnProcessId)) {
        processesNobodyClaims.putIfAbsent(bpmnProcessId, filename);
      }
      // ...preparing the model...
      context = deploymentService.prepareBpmn(
          workflowModuleId,
          context,
          filename,
          bpmnProcessId,
          bpmnModel);
      if (context == null) {
        throw new IllegalStateException(
            """
                Adapter '%s' returned a null processing context from prepareBpmn for BPMN process '%s' \
                of file '%s' of workflow module '%s'! prepareBpmn must always return a non-null \
                context - it is threaded through the whole deployment pipeline."""
                .formatted(
                    deploymentService.getAdapterId(),
                    bpmnProcessId,
                    filename,
                    workflowModuleId));
      }
      // ...wire the business code to the BPMN's tasks...
      deploymentService.wireBpmn(
          workflowModuleId,
          filename,
          bpmnProcessId,
          bpmnModel,
          context);
      // ...and do the wiring for all matching wiring services of extensions. Matching uses
      // assignability of the DECLARED types, the same rule as in
      // startWorkflowProcessing/stopWorkflowProcessing. So an extension is either wired AND
      // started, or neither
      final var currentContext = context;
      wiringServices
          .stream()
          .filter(wiringService -> wiringService.getModelType()
              .isAssignableFrom(deploymentService.getModelType()))
          .filter(wiringService -> wiringService.getProcessContextType()
              .isAssignableFrom(deploymentService.getProcessContextType()))
          .map(wiringService -> (ExtensionWiringService<BPMN, PC>) wiringService)
          .forEach(wiringService -> wiringService.wireBpmn(
              workflowModuleId,
              filename,
              bpmnProcessId,
              bpmnModel,
              currentContext));
    }
    return Optional.of(context);

  }

  /**
   * Starts running the workflows of the given BPMN processes.
   * <p>
   * This does not close the start. {@link #endOfStartup()} writes the box of
   * {@link io.vanillabp.integration.adapter.migration.startup.StartupFindings}. The platform
   * integration calls it when its own start is complete. On Quarkus that is right after this
   * call. On Spring Boot it is earlier, see {@link #endOfStartup()}.
   *
   * @param workflowModuleIds The workflow module IDs to deploy
   * @param <BPMN> The BPMN model type. No model is read here. The parameter is the type to
   *          which an extension's wiring service is cast. The deployment already matched it.
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process.
   */
  public <BPMN, PC> void startWorkflowProcessing(
      final List<String> workflowModuleIds) {

    try {
      this.<BPMN, PC>startWorkflowProcessingOfEachModule(workflowModuleIds);
    } catch (final RuntimeException e) {
      // the box is written unless it was written already. A check which had to throw where
      // it stands, or an adapter which cannot start, ends this start. What was found until
      // then is what the developer needs next to that exception
      properties
          .startupFindings()
          .sayWhatWasFoundBeforeTheStartFailed();
      throw e;
    }

  }

  /**
   * Starts the workflow processing itself, as {@link #startWorkflowProcessing(List)}
   * describes it.
   *
   * @param workflowModuleIds The workflow module IDs to start
   * @param <BPMN> The BPMN model type an extension's wiring service is cast to
   * @param <PC> The processing context of the adapter being started
   */
  @SuppressWarnings("unchecked")
  private <BPMN, PC> void startWorkflowProcessingOfEachModule(
      final List<String> workflowModuleIds) {

    endTheStartIfSomethingWasRefused();

    // walk through all workflow modules...
    workflowModuleIds
        .forEach(workflowModuleId -> {
          final var toBeStarted = this.bpmsProcessingContexts.get(workflowModuleId);
          if (toBeStarted == null) {
            return;
          }
          // ...and all adapters the resources were deployed to (during a BPMS migration all of them keep processing)...
          toBeStarted
              .forEach(bpmsProcessingContext -> {
                final var deploymentService = (AdapterDeploymentService<?, PC>) bpmsProcessingContext.deploymentService;
                final var processingContext = (PC) bpmsProcessingContext.getBpmsProcessingContext();
                // ...and start workflow processing for each adapter
                deploymentService
                    .startWorkflowProcessing(
                        workflowModuleId,
                        processingContext);
              });
        });

    // walk through all workflow modules...
    workflowModuleIds
        .forEach(workflowModuleId -> {
          final var toBeStarted = this.bpmsProcessingContexts.get(workflowModuleId);
          if (toBeStarted == null) {
            return;
          }
          // ...and all adapters the resources were deployed to...
          toBeStarted
              .forEach(bpmsProcessingContext -> {
                final var deploymentService = (AdapterDeploymentService<?, PC>) bpmsProcessingContext.deploymentService;
                final var processingContext = (PC) bpmsProcessingContext.getBpmsProcessingContext();
                // ...and start workflow processing for each extension
                wiringServices
                    .stream()
                    .filter(wiringService -> wiringService.getModelType()
                        .isAssignableFrom(deploymentService.getModelType()))
                    .filter(wiringService -> wiringService.getProcessContextType()
                        .isAssignableFrom(deploymentService.getProcessContextType()))
                    .map(wiringService -> (ExtensionWiringService<BPMN, PC>) wiringService)
                    .forEach(wiringService -> wiringService.startWorkflowProcessing(
                        workflowModuleId,
                        processingContext));
              });
        });

  }

  /**
   * The end of the start. The whole start says what it noticed, in one block. The start
   * ends here if something has to change first.
   * <p>
   * The platform integration calls this once its start is complete. The two platforms reach
   * that moment at different places:
   * <ul>
   *   <li>Quarkus deploys and starts the workflow processing in one observer of the startup
   *       event. So the end of the start is right after
   *       {@link #startWorkflowProcessing(List)}.</li>
   *   <li>Spring Boot deploys while the context is refreshed. It starts the workflow
   *       processing once the application is ready, and a context which is only refreshed
   *       never gets there. So the end of the deployment is the end of the start. What the
   *       start of the workflow processing notices afterwards is a late finding.</li>
   * </ul>
   * A second call does nothing. So a platform which starts several groups of modules does
   * not get several boxes.
   *
   * @throws IllegalStateException Carrying every reason the start cannot go on
   */
  public void endOfStartup() {

    properties
        .startupFindings()
        .endOfStartup();

  }

  /**
   * Stops running the workflows of the given BPMN processes. This is the counterpart of
   * {@link #startWorkflowProcessing(List)}, in reverse order. Extensions are stopped first,
   * in reverse wiring order, then the adapters. The start does it the other way round: it
   * starts adapters before extensions.
   *
   * @param workflowModuleIds The workflow module IDs to stop
   * @param <BPMN> The BPMN model type. No model is read here. The parameter is the type to
   *          which an extension's wiring service is cast. The deployment already matched it.
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process.
   */
  @SuppressWarnings("unchecked")
  public <BPMN, PC> void stopWorkflowProcessing(
      final List<String> workflowModuleIds) {

    final var reversedWiringServices = new LinkedList<>(wiringServices);
    Collections.reverse(reversedWiringServices);

    final var reversedWorkflowModuleIds = new LinkedList<>(workflowModuleIds);
    Collections.reverse(reversedWorkflowModuleIds);

    // walk through all workflow modules (in reverse order)...
    reversedWorkflowModuleIds
        .forEach(workflowModuleId -> {
          final var toBeStopped = this.bpmsProcessingContexts.get(workflowModuleId);
          if (toBeStopped == null) {
            return;
          }
          // ...and all adapters the resources were deployed to...
          toBeStopped
              .forEach(bpmsProcessingContext -> {
                final var deploymentService = (AdapterDeploymentService<?, PC>) bpmsProcessingContext.deploymentService;
                final var processingContext = (PC) bpmsProcessingContext.getBpmsProcessingContext();
                // ...and stop workflow processing for each extension first (reverse of start)
                reversedWiringServices
                    .stream()
                    .filter(wiringService -> wiringService.getModelType()
                        .isAssignableFrom(deploymentService.getModelType()))
                    .filter(wiringService -> wiringService.getProcessContextType()
                        .isAssignableFrom(deploymentService.getProcessContextType()))
                    .map(wiringService -> (ExtensionWiringService<BPMN, PC>) wiringService)
                    .forEach(wiringService -> wiringService.stopWorkflowProcessing(
                        workflowModuleId,
                        processingContext));
              });
        });

    // walk through all workflow modules (in reverse order)...
    reversedWorkflowModuleIds
        .forEach(workflowModuleId -> {
          final var toBeStopped = this.bpmsProcessingContexts.get(workflowModuleId);
          if (toBeStopped == null) {
            return;
          }
          // ...and stop workflow processing for each adapter
          toBeStopped
              .forEach(bpmsProcessingContext -> {
                final var deploymentService = (AdapterDeploymentService<?, PC>) bpmsProcessingContext.deploymentService;
                final var processingContext = (PC) bpmsProcessingContext.getBpmsProcessingContext();
                deploymentService
                    .stopWorkflowProcessing(
                        workflowModuleId,
                        processingContext);
              });
        });

  }

  /**
   * Ends the start if a reason not to start is already known before this step runs.
   * <p>
   * A refusal is collected and thrown at the end of the start, so that a developer who got
   * two things wrong learns both at once. But while it waits, nothing may work on a system
   * outside this application. The checks before the deployment ask the application about
   * itself. Once one of them has refused, the application is about to end. Then deploying
   * BPMN files to a BPMS, or telling adapters to hand out tasks, would be wasted.
   * <p>
   * So the two steps which reach outside look first. What was collected until then is
   * written and thrown here. This also decides which message the developer reads in this
   * case: the one which named a gap in their application, not the one the deployment runs
   * into afterwards.
   */
  private void endTheStartIfSomethingWasRefused() {

    if (properties
        .startupFindings()
        .somethingWasRefused()) {
      properties
          .startupFindings()
          .endOfStartup();
    }

  }

  /**
   * Writes an exception into a finding, with its whole stack trace.
   * <p>
   * The box is one call of the logger, so an exception cannot be handed to the logger next
   * to it. Naming only the message would lose what a developer opens a stack trace for.
   * Writing the trace on a line of its own would bring back the single lines which the box
   * replaced. So the trace goes into the finding, and the finding is as long as the trouble
   * was.
   *
   * @param cause What went wrong
   * @return The stack trace, as the logger would have written it
   */
  private static String whatWentWrong(
      final Throwable cause) {

    final var trace = new java.io.StringWriter();
    try (var writer = new java.io.PrintWriter(trace)) {
      cause.printStackTrace(writer);
    }
    return trace.toString().strip();

  }

}
