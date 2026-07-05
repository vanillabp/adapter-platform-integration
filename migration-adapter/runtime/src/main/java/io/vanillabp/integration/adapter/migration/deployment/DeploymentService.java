package io.vanillabp.integration.adapter.migration.deployment;

import java.io.InputStream;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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
    private AdapterDeploymentService<?, ?, PC> deploymentService;
    private PC bpmsProcessingContext;
  }

  @Getter
  @Setter
  private static class BpmsProcessingContextHolder<PC> {
    private PC bpmsProcessingContext;

    public boolean isEmpty() {
      return bpmsProcessingContext == null;
    }
  }

  /**
   * VanillaBP properties.
   */
  private final MigrationAdapterProperties properties;

  /**
   * Deployment services of all adapters.
   */
  private final List<AdapterDeploymentService<?, ?, ?>> deploymentServices;

  /**
   * Wiring services of all adapters and extensions.
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
      final List<AdapterDeploymentService<?, ?, ?>> deploymentServices,
      final List<ExtensionWiringService<?, ?>> wiringServices) {

    this.properties = properties;
    this.deploymentServices = deploymentServices;
    this.wiringServices = new LinkedList<>(wiringServices);
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
                (AdapterDeploymentService<?, ?, PC>) deploymentService)))
        // and process the resources of the workflow module specific to the adapter
        .forEach(deploymentServiceEntry -> {
          final var workflowModuleId = deploymentServiceEntry.getKey();
          final var deploymentService = deploymentServiceEntry.getValue();
          final var resourceLocationEntry = properties.getAdapterResourcesLocationFor(
              workflowModuleId,
              deploymentService.getAdapterId());
          final var isVanillaBpBpmn = resourceLocationEntry.getValue();
          final var resourceLocation = resourceLocationEntry.getKey();
          // find all BPMN files in the resource location...
          final var bpmsProcessingContext = new BpmsProcessingContextHolder<PC>();
          bpmnResourcesLoader
              .apply(resourceLocation)
              .entrySet()
              // ...and process them...
              .forEach(bpmnFileEntry -> processBpmn(
                  workflowModuleId,
                  deploymentService,
                  bpmsProcessingContext.getBpmsProcessingContext(),
                  bpmnFileEntry.getKey(), // filename
                  bpmnFileEntry.getValue(), // InputStream
                  isVanillaBpBpmn)
                  .ifPresentOrElse(
                      bpmsProcessingContext::setBpmsProcessingContext,
                      () -> log.warn(
                          "File '{}‘ of workflow module '{}' did not contain any executable processes. Skipping deployment of this file!",
                          bpmnFileEntry.getKey(),
                          workflowModuleId)));
          // ...and finally deploy all the resources together (BPMN, DMN) to the BPMS
          deploymentService.deployResources(workflowModuleId, bpmsProcessingContext.getBpmsProcessingContext());
          bpmsProcessingContexts
              .computeIfAbsent(workflowModuleId, id -> new LinkedList<>())
              .add(ToBeStarted
                  .<PC>builder()
                  .deploymentService(deploymentService)
                  .bpmsProcessingContext(bpmsProcessingContext.getBpmsProcessingContext())
                  .build());
        });

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
   * @param <DMN> The DMN model type
   * @param <PC> The processing context, used to store all information needed by the adapter to deploy the process
   * @return The context used to store all information needed by the adapter to deploy the process
   */
  @SuppressWarnings("unchecked")
  private <BPMN, DMN, PC> Optional<PC> processBpmn(
      final String workflowModuleId,
      final AdapterDeploymentService<BPMN, DMN, PC> deploymentService,
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
      // ...wire the business code to the BPMN's tasks...
      deploymentService.wireBpmn(
          workflowModuleId,
          filename,
          bpmnProcessId,
          bpmnModel,
          context);
      // ...and do wiring for all matching extensions wiring services found
      // (extensions receive the same context as the adapter's wireBpmn)
      final var currentContext = context;
      wiringServices
          .stream()
          .filter(wiringService -> wiringService.getModelType().isInstance(bpmnModel))
          .filter(wiringService -> wiringService.getProcessContextType().isInstance(currentContext))
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
                final var deploymentService = (AdapterDeploymentService<?, ?, PC>) bpmsProcessingContext.deploymentService;
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
                final var deploymentService = (AdapterDeploymentService<?, ?, PC>) bpmsProcessingContext.deploymentService;
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

}
