package io.vanillabp.integration.adapter.migration.deployment;

import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

/**
 * A service responsible for deploying BPMS resources like BPMN and DMN files.
 */
@Slf4j
public class DeploymentService {

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
   * Wiring services of extensions only. Since {@link AdapterDeploymentService} extends
   * {@link ExtensionWiringService}, adapters may show up in the list of wiring services
   * passed to the constructor (e.g. collected by type in a bean container) - they are
   * filtered out because adapters are wired explicitly as part of the deployment
   * pipeline.
   */
  private final List<ExtensionWiringService<?, ?>> wiringServices;

  /**
   * A map of workflow module ids and a list of process-contexts to be started after deployment and booting the application.
   * There is one entry per adapter the workflow module was deployed to (the union of the module-level and all
   * workflow-level prioritized adapters), since for BPMS migration all deployed adapters have to keep processing
   * workflows.
   */
  private final Map<String, List<ToBeStarted<?>>> bpmsProcessingContexts;

  /**
   * @param properties Attributes for configuration of the deployment process.
   * @param deploymentServices All adapters deployment services.
   */
  public DeploymentService(
      final MigrationAdapterProperties properties,
      final List<AdapterDeploymentService<?, ?>> deploymentServices,
      final List<ExtensionWiringService<?, ?>> wiringServices) {

    this.properties = properties;
    this.deploymentServices = deploymentServices;
    this.wiringServices = new LinkedList<>(wiringServices
        .stream()
        // adapters are wired explicitly by the deployment pipeline (see field comment)
        .filter(wiringService -> !(wiringService instanceof AdapterDeploymentService))
        .toList());
    this.wiringServices.sort(Comparator.comparingInt(ExtensionWiringService::getOrder));
    bpmsProcessingContexts = new HashMap<>();

  }

  /**
   * Deploy all resources of all workflow modules to the BPMS. This is done
   * <ol>
   *   <li>for each adapter the workflow module has to be deployed to (union of the module-level
   *       and all workflow-level prioritized adapters)</li>
   *   <li>reading the BPMN/DMN files</li>
   *   <li>prepare the BPMN by the adapter (setting default behavior etc.)</li>
   *   <li>for each adapter and extension having the same model type and process context type (e.g. all for Camunda 8)</li>
   *   <ol>
   *     <li>wire the business code to the BPMN's tasks</li>
   *   </ol>
   *   <li>deploy the result to the BPMS</li>
   * </ol>
   * <p>
   * The adapters a workflow module is deployed to are the UNION of the module's
   * effective prioritized-adapters list and every adapter named in a workflow-level
   * <code>prioritized-adapters</code> override of that module
   * ({@link MigrationAdapterProperties#getDeploymentAdaptersFor(String)}): BPMS
   * election is process-granular while deployment is file-granular, so an adapter
   * prioritized for a single workflow only still has to receive the module's
   * resources - otherwise starting that workflow would fail at runtime. Every
   * adapter of the union receives the module's FULL resources (per-process
   * filtering was considered and rejected: BPMN files may contain several processes
   * and adapters deploy whole files - extra processes deployed to an adapter are
   * inert because workflow starts are routed by the election, and during a BPMS
   * migration having the module's complete model in both BPMS is even desirable).
   * <p>
   * A failing deployment aborts booting of the application unless the property
   * <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code> is set to
   * <code>warn</code> for the failing adapter <i>and</i> the adapter is not the
   * first-priority adapter of the workflow module or of any of the module's
   * workflows: in this case the failure is logged and the application still starts
   * (e.g. the old BPMS during a migration being temporarily unreachable). A failure
   * of an adapter being first priority for the module or for a single workflow
   * always fails the boot - new workflows could not be started otherwise.
   * <p>
   * After all adapters were processed, configured workflow IDs
   * (<code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;bpmnProcessId&gt;</code>)
   * which match no executable BPMN process found in the module's resources are
   * reported by a WARN (not a failure - the BPMN may arrive later, e.g. during a
   * migration) naming the known BPMN process IDs.
   *
   * @param workflowModuleIds The workflow module IDs to deploy
   * @param bpmnResourcesLoader A function that takes a resource location and loads the BPMN resources
   *     for a given workflow module ID. It provides a
   *     map having the filename as the key and the BPMN input stream as the value.
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process.
   */
  @SuppressWarnings("unchecked")
  public <PC> void deployResources(
      final List<String> workflowModuleIds,
      final Function<String, Map<String, InputStream>> bpmnResourcesLoader) {

    // several ids of one adapter type only make sense if they address DIFFERENT
    // systems - which the ADAPTER decides
    validateDistinctAdapterInstances();

    final var knownBpmnProcessIds = new HashMap<String, Set<String>>();

    // walk through all workflow modules
    workflowModuleIds
        .stream()
        // for each adapter the module has to be deployed to (union of the
        // module-level and all workflow-level prioritized adapters)...
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
        // and process the resources of the workflow module specific to the adapter
        .forEach(deploymentServiceEntry -> {
          final var workflowModuleId = deploymentServiceEntry.getKey();
          final var deploymentService = deploymentServiceEntry.getValue();
          try {
            deployResourcesOfAdapter(
                workflowModuleId,
                deploymentService,
                bpmnResourcesLoader,
                knownBpmnProcessIds.computeIfAbsent(workflowModuleId, id -> new HashSet<>()));
          } catch (final RuntimeException e) {
            final var adapterId = deploymentService.getAdapterId();
            final var policy = properties.getDeploymentFailureFor(adapterId);
            if ((policy == DeploymentFailurePolicy.WARN) && !properties.isFirstPriorityFor(workflowModuleId,
                adapterId)) {
              log.warn(
                  "Deployment of workflow module '{}' failed for adapter '{}'! Since "
                      + "'{}.adapters.{}.deployment-failure' is set to 'warn' and the adapter is not "
                      + "the first-priority adapter of the workflow module or of any of its workflows, "
                      + "the application starts anyway. Workflows of this "
                      + "workflow module are not processed by adapter '{}'!",
                  workflowModuleId,
                  adapterId,
                  MigrationAdapterProperties.PREFIX,
                  adapterId,
                  adapterId,
                  e);
            } else {
              throw e;
            }
          }
        });

    reportWorkflowModulesWithoutResources(workflowModuleIds);

    warnAboutConfiguredWorkflowsUnknownToBpmnResources(workflowModuleIds, knownBpmnProcessIds);

  }

  /**
   * Reports a workflow module NO adapter found resources for. Each adapter already
   * warned about its own location, but a module none of them could serve is a
   * different message: nothing of this module runs, and the failure a developer meets
   * later comes from the BPMS ("no processes deployed with key ...") and names neither
   * VanillaBP nor a location.
   * <p>
   * The boot continues on purpose: BPMN may arrive with an adapter deployed later
   * (the migration case this platform exists for), and a module without resources
   * breaks nothing by itself.
   *
   * @param workflowModuleIds The workflow module IDs which were deployed
   */
  private void reportWorkflowModulesWithoutResources(
      final List<String> workflowModuleIds) {

    workflowModuleIds
        .stream()
        .filter(workflowModuleId -> !bpmsProcessingContexts.containsKey(workflowModuleId))
        .forEach(workflowModuleId -> log.error(
            "No BPMN resources were found for workflow module '{}' - by NONE of its adapters ({}). "
                + "No workflow of this module can be started; the BPMS would report an unknown "
                + "process. Locations searched: {}. Check where the module's BPMN files are packaged, "
                + "or name the location explicitly in "
                + "'{}.workflow-modules.{}.adapters.<adapter>.resources-location'.",
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
            workflowModuleId));

  }

  /**
   * Asks each adapter type whose ids are configured more than once whether those
   * ids actually address DIFFERENT systems
   * ({@link AdapterDeploymentService#validateDistinctAdapterInstances(List)}).
   * Two instances of one BPMS sharing the same database/endpoint/credentials are
   * the same instance - configuring them as separate adapters is a defect the
   * adapter reports with a guiding message.
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
      // in the order they are prioritized - the adapter's message may name them
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
   * Reports configured workflow IDs
   * (<code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;bpmnProcessId&gt;</code>)
   * matching no executable BPMN process found in the module's resources. Only a
   * WARN, consistent with the handling of configured workflow modules missing in the
   * classpath: the BPMN may arrive later (e.g. during a BPMS migration), so booting
   * must not be prevented. Runs after {@link #deployResources(List, Function)}
   * processed all adapters because BPMN process IDs are known only after the
   * adapters' <code>readBpmn</code> - IDs found by ANY adapter count as known (the
   * resources location may differ per adapter).
   *
   * @param workflowModuleIds The workflow module IDs deployed
   * @param knownBpmnProcessIds The executable BPMN process IDs found per workflow
   *          module
   */
  private void warnAboutConfiguredWorkflowsUnknownToBpmnResources(
      final List<String> workflowModuleIds,
      final Map<String, Set<String>> knownBpmnProcessIds) {

    workflowModuleIds.forEach(workflowModuleId -> {
      final var workflowModule = properties.getWorkflowModules().get(workflowModuleId);
      if ((workflowModule == null) || workflowModule.getWorkflows().isEmpty()) {
        return;
      }
      final var knownProcessIds = knownBpmnProcessIds.getOrDefault(workflowModuleId, Set.of());
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
      log.warn(
          """
              Found properties for BPMN processes
                {}.workflow-modules.{}.workflows.{}
              which were not found in any BPMN resource of workflow module '{}'! These properties are
              never used - fix the BPMN process ID or add the BPMN file. This may be intended if the
              BPMN arrives later (e.g. during a BPMS migration). Executable BPMN process IDs known for
              this workflow module are: {}.""",
          MigrationAdapterProperties.PREFIX,
          workflowModuleId,
          String.join(propPrefix, unknownConfiguredWorkflows),
          workflowModuleId,
          knownProcessIds.isEmpty()
              ? "none"
              : "'%s'".formatted(String.join("', '", knownProcessIds.stream().sorted().toList())));
    });

  }

  /**
   * Deploys the resources of the given workflow module using the given adapter's
   * deployment service and remembers the resulting processing context for
   * {@link #startWorkflowProcessing(List)}.
   *
   * @param workflowModuleId The workflow module ID
   * @param deploymentService The deployment service to use
   * @param bpmnResourcesLoader A function that takes a resource location and loads the BPMN resources
   * @param knownBpmnProcessIds Collects the executable BPMN process IDs found (used
   *     to validate configured workflow IDs after all adapters were processed)
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process.
   */
  private <PC> void deployResourcesOfAdapter(
      final String workflowModuleId,
      final AdapterDeploymentService<?, PC> deploymentService,
      final Function<String, Map<String, InputStream>> bpmnResourcesLoader,
      final Set<String> knownBpmnProcessIds) {

    // a configured location is the only one; the convention may name two (the
    // application IS the workflow module, and a module tested inside its own Maven
    // module is that as well while keeping its files below the module ID).
    // The first location holding files wins - never both, so a process cannot be
    // deployed twice.
    final var candidateLocations = properties.getAdapterResourcesLocationsFor(
        workflowModuleId,
        deploymentService.getAdapterId());
    var searchedLocation = candidateLocations.getFirst();
    var foundFiles = Map.<String, InputStream>of();
    for (final var candidate : candidateLocations) {
      final var filesOfCandidate = bpmnResourcesLoader.apply(candidate.location());
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
          // ...and process them...
          .forEach(bpmnFileEntry -> processBpmn(
              workflowModuleId,
              deploymentService,
              bpmsProcessingContext.getBpmsProcessingContext(),
              bpmnFileEntry.getKey(), // filename
              bpmnFileEntry.getValue(), // InputStream
              resourcesLocation.vanillaBpBpmn(),
              knownBpmnProcessIds)
              .ifPresentOrElse(
                  bpmsProcessingContext::setBpmsProcessingContext,
                  () -> log.warn(
                      "File '{}' of workflow module '{}' did not contain any executable processes. Skipping deployment of this file!",
                      bpmnFileEntry.getKey(),
                      workflowModuleId)));
    } finally {
      // streams are opened by the platform's resources loader and owned by this
      // pipeline: close ALL of them regardless of the processing outcome - also
      // the ones never processed because an earlier file failed (adapters must
      // not close them, see AdapterDeploymentService#readBpmn)
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

    // zero executable processes (empty/missing location or only non-executable
    // BPMN): warn with the key to change and skip this adapter for this module -
    // the adapter must never be called with a null processing context
    if (bpmsProcessingContext.getBpmsProcessingContext() == null) {
      log.warn(
          "No executable BPMN processes found for workflow module '{}' at location {}! "
              + "Adapter '{}' is skipped for this workflow module, so none of its workflows can be "
              + "started by that adapter. If this is unintended, check property "
              + "'{}.workflow-modules.{}.adapters.{}.resources-location' (or '{}.resources-location') "
              + "and the BPMN files at that location.",
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
          MigrationAdapterProperties.PREFIX);
      return;
    }

    // ...and finally deploy all the resources together (BPMN, DMN) to the BPMS
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
   * Process the given BPMN file.
   *
   * @param workflowModuleId The workflow module ID
   * @param deploymentService The deployment service to use
   * @param bpmsProcessingContext The context used to store all information needed by the adapter to deploy the process
   * @param filename The filename of the BPMN file (used for logging and error messages)
   * @param bpmn The BPMN resource inputstream
   * @param isVanillaBpBpmn Whether the BPMN is VanillaBP's BPMN or is specific to the adapter's BPMS
   * @param knownBpmnProcessIds Collects the executable BPMN process IDs found
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
      final Set<String> knownBpmnProcessIds) {

    // read executable processes from the BPMN file
    final var executableProcesses = deploymentService.readBpmn(
        workflowModuleId,
        filename,
        bpmn,
        isVanillaBpBpmn);
    if (executableProcesses.isEmpty()) {
      return Optional.empty();
    }

    // process each executable process, threading the processing context accumulated
    // so far through ALL processes of the file...
    var context = bpmsProcessingContext;
    for (final var processIdAndModel : executableProcesses) {
      final var bpmnProcessId = processIdAndModel.getKey();
      final var bpmnModel = processIdAndModel.getValue();
      knownBpmnProcessIds.add(bpmnProcessId);
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
      // ...and do wiring for all matching extensions wiring services found:
      // matching uses DECLARED-type assignability - the same rule as
      // startWorkflowProcessing/stopWorkflowProcessing - so an extension is either
      // consistently wired AND started or consistently neither
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
    return Optional.ofNullable(context);

  }

  /**
   * Starts to running the workflows of the given BPMN processes.
   *
   * @param workflowModuleIds The workflow module IDs to deploy
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process.
   */
  @SuppressWarnings("unchecked")
  public <BPMN, PC> void startWorkflowProcessing(
      final List<String> workflowModuleIds) {

    // walk through all workflow modules...
    workflowModuleIds
        .forEach(workflowModuleId -> {
          final var toBeStarted = this.bpmsProcessingContexts.get(workflowModuleId);
          if (toBeStarted == null) {
            return;
          }
          // ...and all adapters resources were deployed to (for BPMS migration all of them keep processing)...
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
          // ...and all adapters resources were deployed to...
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
   * Stops running the workflows of the given BPMN processes. This is the counterpart
   * of {@link #startWorkflowProcessing(List)} and is executed in reverse order:
   * extensions are stopped first (in reverse wiring order), then the adapters —
   * mirroring the start sequence where adapters are started before extensions.
   *
   * @param workflowModuleIds The workflow module IDs to stop
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
          // ...and all adapters resources were deployed to...
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

}
