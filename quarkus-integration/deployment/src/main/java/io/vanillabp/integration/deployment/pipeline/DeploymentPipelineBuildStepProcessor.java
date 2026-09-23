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
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.deployment.parts.PartsCheckedBuildItem;
import io.vanillabp.integration.deployment.workflowmodule.VanillaBpWorkflowModulesBuildItem;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.runtime.deployment.BpmsResourceIndex;
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
   * Quarkus builds this processor while it augments the application and calls the build
   * steps below on it. Nothing else builds it, and no step keeps state in it.
   */
  public DeploymentPipelineBuildStepProcessor() {
  }

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
   * Indexes the <code>.bpmn</code> and <code>.dmn</code> resources of all application
   * archives at build time and records them (as plain classpath-root-relative strings) together with
   * the detected workflow module IDs as the synthetic {@link BpmsResourceIndex}
   * bean. The runtime deployment runner filters the index by the configured
   * <code>resources-location</code> - RUN_TIME configuration cannot be
   * pattern-scanned in a Quarkus fast-jar at runtime.
   * <p>
   * Dev mode watches the same kind of files, but by extension and not by name. A build
   * writes the index once, so a file which arrives afterwards is in none of the lists a
   * build produced. Watching the names of that build would leave such a file out of the
   * restart as well, and the application would go on running against an index which no
   * longer says what the directory holds. Watching the extension covers both: the files
   * of the last build, and the one a developer adds while the application runs.
   * <p>
   * The indexed files are registered for the native image as well. A native image only
   * carries the resources it was told about, so without that registration the index would
   * name files the running application cannot open, and the deployment pipeline would end
   * the startup saying exactly that.
   *
   * @param applicationArchives The archives of this Quarkus build
   * @param workflowModulesFound Information about all workflow modules found
   * @param partsChecked Waited for, so a build whose VanillaBP parts do not belong together ends
   *     before anything is indexed
   * @param watchedFiles Producer registering the file extensions for dev-mode hot deployment
   * @param nativeImageResources Producer putting the files into the native image
   * @param syntheticBeans Producer used to register the recorded index as a bean
   * @param recorder The recorder building the runtime object
   */
  @Record(ExecutionTime.RUNTIME_INIT)
  @BuildStep
  void indexBpmsResources(
      final ApplicationArchivesBuildItem applicationArchives,
      final VanillaBpWorkflowModulesBuildItem workflowModulesFound,
      final PartsCheckedBuildItem partsChecked,
      final BuildProducer<HotDeploymentWatchedFileBuildItem> watchedFiles,
      final BuildProducer<NativeImageResourceBuildItem> nativeImageResources,
      final BuildProducer<SyntheticBeanBuildItem> syntheticBeans,
      final DeploymentRecorder recorder) {

    // sorted + deduplicated: the same path may show up in more than one archive
    final var resourcePaths = new TreeSet<String>();
    applicationArchives
        .getAllArchives()
        .forEach(archive -> archive
            .accept(openPathTree -> openPathTree
                .walk(visit -> Optional
                    .ofNullable(visit.getRelativePath("/"))
                    .filter(DeploymentPipelineBuildStepProcessor::isBpmsResource)
                    .ifPresent(resourcePaths::add))));

    watchedFiles
        .produce(HotDeploymentWatchedFileBuildItem
            .builder()
            .setLocationPredicate(DeploymentPipelineBuildStepProcessor::isBpmsResource)
            .build());

    resourcePaths
        .forEach(path -> nativeImageResources.produce(new NativeImageResourceBuildItem(path)));

    // pass items in serializable kinds of list (recorder bytecode serialization)
    final var workflowModuleIds = workflowModulesFound
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        // the same workflow module may be provided by more than one archive
        .distinct()
        .sorted()
        .collect(java.util.stream.Collectors.toCollection(LinkedList::new));

    final var index = recorder.recordBpmsResourceIndex(
        workflowModuleIds,
        new LinkedList<>(resourcePaths));

    syntheticBeans
        .produce(SyntheticBeanBuildItem
            .configure(BpmsResourceIndex.class)
            .scope(ApplicationScoped.class)
            .runtimeValue(index)
            .setRuntimeInit()
            .done());

  }

  /**
   * @param path A path relative to the classpath root or to a resource root
   * @return Whether the file is one the deployment pipeline reads
   */
  private static boolean isBpmsResource(
      final String path) {

    return path.endsWith(DeploymentService.BPMN_EXTENSION) || path.endsWith(DeploymentService.DMN_EXTENSION);

  }

}
