package io.vanillabp.integration.deployment.pipeline;

import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.deployment.workflowmodule.VanillaBpWorkflowModulesBuildItem;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.runtime.deployment.BpmnResourceIndex;
import io.vanillabp.integration.runtime.deployment.DeploymentRecorder;
import io.vanillabp.integration.runtime.deployment.VanillaBpDeploymentRunner;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * VanillaBP extension build step processor wiring the runtime deployment pipeline:
 * the startup runner executing
 * <code>readBpmn &rarr; prepareBpmn &rarr; wireBpmn &rarr; deployResources &rarr;
 * startWorkflowProcessing</code> per workflow module, the adapters' deployment
 * services announced via {@link VanillaBpAdapterDeploymentServiceBuildItem} and the
 * build-time index of all BPMN resources (RUN_TIME <code>resources-location</code>
 * cannot be pattern-scanned in a fast-jar at runtime).
 */
public class DeploymentPipelineBuildStepProcessor {

  /**
   * Registers the adapters' deployment-service beans announced via
   * {@link VanillaBpAdapterDeploymentServiceBuildItem}: adapters only produce the
   * build item (adapter type + bean class), the VanillaBP extension registers the
   * bean - no separate self-registration needed.
   *
   * @param deploymentServicesProvidedByAdapters The build items produced by the adapters
   * @param additionalBeans Producer used to register the announced beans
   */
  @BuildStep
  void buildAdapterDeploymentServiceBeans(
      final List<VanillaBpAdapterDeploymentServiceBuildItem> deploymentServicesProvidedByAdapters,
      final BuildProducer<AdditionalBeanBuildItem> additionalBeans) {

    deploymentServicesProvidedByAdapters
        .stream()
        .map(VanillaBpAdapterDeploymentServiceBuildItem::getDeploymentServiceBeanClass)
        .filter(beanClass -> (beanClass != null) && !beanClass.isBlank())
        .forEach(beanClass -> additionalBeans.produce(AdditionalBeanBuildItem
            .builder()
            .addBeanClass(beanClass)
            .setUnremovable() // don't remove, since it is used under the hoods
            .build()));

  }

  /**
   * Keeps deployment-pipeline beans from ArC's unused-bean removal: adapters'
   * {@link AdapterDeploymentService} and extensions' {@link ExtensionWiringService}
   * <i>element</i> beans are not injected by application code but collected via
   * <code>Instance</code> lookups by the {@link VanillaBpDeploymentRunner}. (The
   * per-adapter-id <code>List</code> shape is kept by the platform's
   * <code>keepPerAdapterIdListBeans</code> build step.)
   *
   * @return The unremovable-bean build item
   */
  @BuildStep
  UnremovableBeanBuildItem keepDeploymentPipelineBeans() {

    return UnremovableBeanBuildItem.beanTypes(
        AdapterDeploymentService.class,
        ExtensionWiringService.class);

  }

  /**
   * Registers the {@link VanillaBpDeploymentRunner} driving the deployment pipeline
   * on {@link io.quarkus.runtime.StartupEvent}. It is marked unremovable because it
   * is not injected by application code but driven by lifecycle events.
   *
   * @return The additional {@link VanillaBpDeploymentRunner} bean
   */
  @BuildStep
  AdditionalBeanBuildItem buildDeploymentRunner() {

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(VanillaBpDeploymentRunner.class)
        .setUnremovable() // don't remove, since it is used under the hoods
        .build();

  }

  /**
   * Indexes all <code>.bpmn</code> resources of all application archives at build
   * time and records them (as plain classpath-root-relative strings) together with
   * the detected workflow module IDs as the synthetic {@link BpmnResourceIndex}
   * bean. The runtime deployment runner filters the index by the configured
   * <code>resources-location</code> - RUN_TIME configuration cannot be
   * pattern-scanned in a Quarkus fast-jar at runtime. The indexed files are also
   * watched for dev-mode hot deployment (note: only files existing at build time
   * are watched; adding a NEW BPMN file in dev mode requires touching a watched
   * file or restarting).
   *
   * @param applicationArchives The archives of this Quarkus build
   * @param workflowModulesFound Information about all workflow modules found
   * @param watchedFiles Producer registering the BPMN files for dev-mode hot deployment
   * @param syntheticBeans Producer used to register the recorded index as a bean
   * @param recorder The recorder building the runtime object
   */
  @Record(ExecutionTime.RUNTIME_INIT)
  @BuildStep
  void indexBpmnResources(
      final ApplicationArchivesBuildItem applicationArchives,
      final VanillaBpWorkflowModulesBuildItem workflowModulesFound,
      final BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles,
      final BuildProducer<SyntheticBeanBuildItem> syntheticBeans,
      final DeploymentRecorder recorder) {

    // sorted + deduplicated: the same path may show up in more than one archive
    final var bpmnResourcePaths = new TreeSet<String>();
    applicationArchives
        .getAllApplicationArchives()
        .forEach(archive -> archive
            .accept(openPathTree -> openPathTree
                .walk(visit -> Optional
                    .ofNullable(visit.getRelativePath("/"))
                    .filter(relativePath -> relativePath.endsWith(".bpmn"))
                    .ifPresent(bpmnResourcePaths::add))));

    bpmnResourcePaths
        .forEach(path -> watchedFiles.produce(new HotDeploymentWatchedFileBuildItem(path)));

    // pass items in serializable kinds of list (recorder bytecode serialization)
    final var workflowModuleIds = workflowModulesFound
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        // the same workflow module may be provided by more than one archive
        .distinct()
        .sorted()
        .collect(java.util.stream.Collectors.toCollection(LinkedList::new));

    final var index = recorder.recordBpmnResourceIndex(
        workflowModuleIds,
        new LinkedList<>(bpmnResourcePaths));

    syntheticBeans
        .produce(SyntheticBeanBuildItem
            .configure(BpmnResourceIndex.class)
            .scope(ApplicationScoped.class)
            .runtimeValue(index)
            .setRuntimeInit()
            .done());

  }

}
