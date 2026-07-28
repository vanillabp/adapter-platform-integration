package io.vanillabp.integration.adapter.migration.deployment;

import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
   * There is one entry per adapter configured for the workflow module (prioritized adapters), since for BPMS migration
   * all deployed adapters have to keep processing workflows.
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
   *   <li>for each adapter configured for the workflow-module (prioritized adapters)</li>
   *   <li>reading the BPMN/DMN files</li>
   *   <li>prepare the BPMN by the adapter (setting default behavior etc.)</li>
   *   <li>for each adapter and extension having the same model type and process context type (e.g. all for Camunda 8)</li>
   *   <ol>
   *     <li>wire the business code to the BPMN's tasks</li>
   *   </ol>
   *   <li>deploy the result to the BPMS</li>
   * </ol>
   * <p>
   * A failing deployment aborts booting of the application unless the property
   * <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code> is set to
   * <code>warn</code> for the failing adapter <i>and</i> the adapter is not the
   * first-priority adapter of the workflow module: in this case the failure is
   * logged and the application still starts (e.g. the old BPMS during a migration
   * being temporarily unreachable). A failure of the first-priority adapter always
   * fails the boot.
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

    // walk through all workflow modules
    workflowModuleIds
        .stream()
        // for each adapter configured...
        .flatMap(workflowModuleId -> properties
            .getPrioritizedAdaptersFor(workflowModuleId)
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
                bpmnResourcesLoader);
          } catch (final RuntimeException e) {
            final var adapterId = deploymentService.getAdapterId();
            final var firstPriorityAdapter = properties
                .getPrioritizedAdaptersFor(workflowModuleId)
                .getFirst();
            final var policy = properties.getDeploymentFailureFor(adapterId);
            if ((policy == DeploymentFailurePolicy.WARN) && !firstPriorityAdapter.equals(adapterId)) {
              log.warn(
                  "Deployment of workflow module '{}' failed for adapter '{}'! Since "
                      + "'{}.adapters.{}.deployment-failure' is set to 'warn' and the adapter is not "
                      + "the first-priority adapter, the application starts anyway. Workflows of this "
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

  }

  /**
   * Deploys the resources of the given workflow module using the given adapter's
   * deployment service and remembers the resulting processing context for
   * {@link #startWorkflowProcessing(List)}.
   *
   * @param workflowModuleId The workflow module ID
   * @param deploymentService The deployment service to use
   * @param bpmnResourcesLoader A function that takes a resource location and loads the BPMN resources
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process.
   */
  private <PC> void deployResourcesOfAdapter(
      final String workflowModuleId,
      final AdapterDeploymentService<?, PC> deploymentService,
      final Function<String, Map<String, InputStream>> bpmnResourcesLoader) {

    final var resourcesLocation = properties.getAdapterResourcesLocationFor(
        workflowModuleId,
        deploymentService.getAdapterId());
    // find all BPMN files in the resource location...
    final var bpmsProcessingContext = new BpmsProcessingContextHolder<PC>();
    final var bpmnFiles = bpmnResourcesLoader.apply(resourcesLocation.location());
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
              resourcesLocation.vanillaBpBpmn())
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
          "No executable BPMN processes found for workflow module '{}' at location '{}'! "
              + "Adapter '{}' is skipped for this workflow module. If this is unintended, "
              + "check property '{}.workflow-modules.{}.adapters.{}.resources-location' "
              + "(or '{}.resources-location') and the BPMN files at that location.",
          workflowModuleId,
          resourcesLocation.location(),
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
      final boolean isVanillaBpBpmn) {

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
