package io.vanillabp.integration.deployment.processservice;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ParameterizedType;
import org.jboss.jandex.Type;

import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.SyntheticBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.ServiceStartBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.vanillabp.integration.deployment.config.MigrationAdapterPropertiesBuildItem;
import io.vanillabp.integration.deployment.workflowmodule.VanillaBpWorkflowModulesBuildItem;
import io.vanillabp.integration.deployment.workflowmodule.WorkflowModuleBuildStepProcessor;
import io.vanillabp.integration.runtime.processservice.ProcessServiceCdiBean;
import io.vanillabp.integration.runtime.processservice.ProcessServiceCdiBeanRecorder;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * VanillaBP extension build step processor, responsible for building {@link ProcessService} beans.
 */
@Slf4j
public class ProcessServiceBuildStepProcessor {

  public static String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_AGGREGATECLASS = "workflowAggregateClass";
  public static String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS = "bpmnProcess";
  public static String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS_BPMNPROCESSID = "bpmnProcessId";

  /**
   * Build step for build {@link ProcessService} beans for all services
   * annotated by {@link WorkflowService} at class level.
   *
   * @param indexBuildItem Classes of the project and its dependencies (if Jandix available)
   * @param applicationArchivesBuildItem Information about All archives (JARs and directories) of the project
   * @param migrationAdapterProperties Properties of the migration adapter previously built and validated
   * @param workflowModulesFound Information about all workflow modules found in the project
   * @param processServiceRecorder Recorder for {@link ProcessService} beans
   * @param processServiceProducer {@link BuildProducer} for {@link ProcessServiceBuildItem} used to collect {@link ProcessService} beans
   * @param syntheticBeanProducer {@link BuildProducer} for {@link SyntheticBeanBuildItem} used to define {@link ProcessService} beans based on their generic parameter.
   */
  @Record(ExecutionTime.STATIC_INIT)
  @BuildStep
  void buildProcessServices(
      final BeanArchiveIndexBuildItem indexBuildItem,
      final ApplicationArchivesBuildItem applicationArchivesBuildItem,
      final MigrationAdapterPropertiesBuildItem migrationAdapterProperties,
      final VanillaBpWorkflowModulesBuildItem workflowModulesFound,
      final ProcessServiceCdiBeanRecorder processServiceRecorder,
      final BuildProducer<ProcessServiceBuildItem> processServiceProducer,
      final BuildProducer<SyntheticBeanBuildItem> syntheticBeanProducer) {

    // scan for classes annotated by @WorkflowService
    final Set<Type> processServicesBuilt = new HashSet<>();
    indexBuildItem
        .getIndex()
        .getAnnotations(WorkflowService.class)
        // and build an adapter-aware process service for each workflow aggregate class
        .forEach(annotation -> {

          try {
            final var workflowAggregateType = annotation
                .value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_AGGREGATECLASS)
                .asClass();
            // if there is more than one @WorkflowService class for a specific BPMN process ID,
            // then use the one previously built
            if (processServicesBuilt.contains(workflowAggregateType)) {
              return;
            }

            // collect information necessary for bean creation
            final var serviceClass = annotation.target().asClass();
            final var workflowModuleId = WorkflowModuleBuildStepProcessor
                .getWorkflowModuleId(
                    workflowModulesFound,
                    applicationArchivesBuildItem,
                    serviceClass);
            final var bpmnProcessId = Optional
                .ofNullable(annotation.value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS))
                .map(AnnotationValue::asNested)
                .map(a -> a.value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS_BPMNPROCESSID))
                .map(AnnotationValue::asString)
                .orElse(serviceClass.simpleName());

            // build bean, and BuildItems for the bean
            final var processService = processServiceRecorder.recordProcessService(
                workflowModuleId,
                bpmnProcessId,
                workflowAggregateType.toString(),
                migrationAdapterProperties.getProperties());
            // ProcessServiceBuildItem is used for handing over the bean to subsequent build steps
            processServiceProducer.produce(new ProcessServiceBuildItem(processService));
            // Define a CDI {@link ProcessService} bean based on the workflow aggregate class generic parameter.
            syntheticBeanProducer.produce(SyntheticBeanBuildItem
                .configure(ProcessServiceCdiBean.class)
                .types(ParameterizedType.create(ProcessService.class, workflowAggregateType))
                .scope(Singleton.class)
                .name("VanillaBP_ProcessService_%s".formatted(workflowAggregateType.toString()))
                .runtimeValue(processService)
                .setRuntimeInit()
                .unremovable()
                .done());
            processServicesBuilt.add(workflowAggregateType);
          } catch (ClassNotFoundException e) {
            log.info("NoClassDefFoundError: it might be an optional dependency", e);
          }
        });

  }

  /**
   * Build step for runtime initialization of {@link ProcessService} beans. At runtime
   * configuration validation is done based on data collected from BPMS (like
   * backwards compatibility checks for BPMN versions deployed by previous versions of
   * the business software project).
   *
   * @param processServiceRecorder Recorder for {@link ProcessService} beans
   * @param shutdownContextBuildItem The build item providing a runtime shutdown hook, used for tearing down {@link ProcessService}
   * @param processServiceBuildItems All {@link ProcessService} beans provided previous build steps
   * @return The build item telling Quarkus about the need for custom runtime bean initialization
   */
  @Record(ExecutionTime.RUNTIME_INIT)
  @BuildStep
  ServiceStartBuildItem initializeProcessServices(
      final ProcessServiceCdiBeanRecorder processServiceRecorder,
      final ShutdownContextBuildItem shutdownContextBuildItem,
      final List<ProcessServiceBuildItem> processServiceBuildItems) {

    // record runtime bean initialization for each ProcessService bean
    processServiceBuildItems
        .forEach(
            processService -> processServiceRecorder
                .startProcessService(shutdownContextBuildItem, processService.getProcessService()));

    return new ServiceStartBuildItem("VanillaBpProcessService");

  }

}
