package io.vanillabp.integration.deployment.processservice;

import static io.quarkus.gizmo.Type.classType;
import static io.quarkus.gizmo.Type.parameterizedType;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.Type;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.SignatureBuilder;
import io.vanillabp.integration.deployment.config.MigrationAdapterPropertiesBuildItem;
import io.vanillabp.integration.deployment.validation.EnsureClassIsBeanValidationBuildItem;
import io.vanillabp.integration.deployment.workflowmodule.VanillaBpWorkflowModulesBuildItem;
import io.vanillabp.integration.deployment.workflowmodule.WorkflowModuleBuildStepProcessor;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;
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
   * @param applicationArchivesBuildItem Information about All archives (JARs and directories) of the project
   * @param migrationAdapterProperties Properties of the migration adapter previously built and validated as a dependency
   * @param workflowModulesFound Information about all workflow modules found in the project
   * @param generatedBeanBuildItemBuildProducer {@link BuildProducer} used to collect generated {@link ProcessService} beans
   * @param additionalBeanBuildItemBuildProducer {@link BuildProducer} used to collect beans provided in module "runtime"
   */
  @BuildStep
  void buildProcessServices(
      final ApplicationArchivesBuildItem applicationArchivesBuildItem,
      final MigrationAdapterPropertiesBuildItem migrationAdapterProperties,
      final VanillaBpWorkflowModulesBuildItem workflowModulesFound,
      final BuildProducer<EnsureClassIsBeanValidationBuildItem> ensureClassIsBeanBuildItemProducer,
      final BuildProducer<GeneratedBeanBuildItem> generatedBeanBuildItemBuildProducer,
      final BuildProducer<AdditionalBeanBuildItem> additionalBeanBuildItemBuildProducer) {

    final var aggregatePersistenceAwares = applicationArchivesBuildItem
        // search all archives of the project
        .getAllApplicationArchives()
        .stream()
        .flatMap(archive -> archive
            // collect each archive's known implementations
            .getIndex()
            .getAllKnownImplementations(AggregatePersistenceAware.class)
            .stream()
            .map(aware -> Map.entry(aware, archive)))
        .toList();

    // scan for classes annotated by @WorkflowService
    final Set<Type> processServicesBuilt = new HashSet<>();
    applicationArchivesBuildItem
        // search all archives of the project
        .getAllApplicationArchives()
        .stream()
        .flatMap(archive -> archive
            .getIndex()
            .getAnnotations(WorkflowService.class)
            .stream())
        // and build an adapter-aware process service for each workflow aggregate class
        .forEach(annotation -> {

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
          // ensure service class will be a CDI bean at runtime
          ensureClassIsBeanBuildItemProducer
              .produce(EnsureClassIsBeanValidationBuildItem
                  .builder()
                  .className(serviceClass.name())
                  .usageDescription("Workflow service annotated with @"
                      + WorkflowService.class.getName())
                  .build());

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

          // find persistence support class for the aggregate class
          final var aggregatePersistenceType = aggregatePersistenceAwares
              .stream()
              // calculate distance of classes
              .map(awareEntry -> Map.entry(
                  awareEntry.getKey(),
                  AggregatePersistenceResolver.distance(
                      awareEntry.getValue().getIndex(),
                      awareEntry.getKey(),
                      workflowAggregateType.name()
                  )))
              // filter persistence awares those aggregate type is not assignable to the current aggregate type
              .filter(awareEntry -> awareEntry.getValue() != Integer.MAX_VALUE)
              // choose the most specific persistence support in terms of inheritance class distance
              .min(Comparator.comparingInt(Map.Entry::getValue))
              // if none found, fall back to persistence support based on Spring Data Util bean
              .map(Map.Entry::getKey)
              .orElseThrow(() -> new IllegalStateException(
                  "You have to provide a CDI bean implementing\n  "
                      + AggregatePersistenceAware.class.getName()
                      + "\nwhich is responsible to persist aggregates.\n"
                      + "This is necessary because in Quarkus there is no unique way to do persistence of entities:\n"
                      + "- Active record pattern: https://quarkus.io/guides/hibernate-orm-panache#solution-1-using-the-active-record-pattern\n"
                      + "- Repository record pattern: https://quarkus.io/guides/hibernate-orm-panache#solution-2-using-the-repository-pattern\n"
                      + "- Spring Data pattern: https://quarkus.io/guides/spring-data-jpa"
              ));
          // ensure service class will be a CDI bean at runtime
          ensureClassIsBeanBuildItemProducer
              .produce(EnsureClassIsBeanValidationBuildItem
                  .builder()
                  .className(aggregatePersistenceType.name())
                  .usageDescription("Service implementing the interface "
                      + AggregatePersistenceAware.class.getName())
                  .build());

          // generate process service CDI bean specific to the workflow aggregate
          generateProcessService(
              generatedBeanBuildItemBuildProducer,
              workflowModuleId,
              bpmnProcessId,
              "%s.ProcessService_%s".formatted(
                  workflowAggregateType.name().packagePrefix(),
                  workflowAggregateType.name().withoutPackagePrefix()),
              aggregatePersistenceType,
              workflowAggregateType);

          // prevent building more than one process service even if a workflow aggregate class
          // is used in more than one @WorkflowService annotated service
          processServicesBuilt.add(workflowAggregateType);

        });

  }

  /**
   * Generate process service CDI bean specific to the workflow aggregate type given.
   *
   * @param generatedBeanBuildItemBuildProducer The producer used to build multiple beans if necessary
   * @param workflowModuleId The ID of the workflow module the service belongs to
   * @param bpmnProcessId The BPMN process ID the service is for
   * @param className The class name of the service to build
   * @param aggregatePersistenceType The aggregate persistence type to be used by the service
   * @param workflowAggregateType The workflow aggregate type the service is for
   */
  private void generateProcessService(
      final BuildProducer<GeneratedBeanBuildItem> generatedBeanBuildItemBuildProducer,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String className,
      final ClassInfo aggregatePersistenceType,
      final Type workflowAggregateType) {

    final var aggregatePersistenceClassName = aggregatePersistenceType.name().toString();
    final var aggregateClassName = workflowAggregateType.name().toString();

    /*
     * public class ProcessService_Aggregate extends ProcessServiceBaseCdiBean<Aggregate> {
     */
    final var beanClassOutput = new GeneratedBeanGizmoAdaptor(generatedBeanBuildItemBuildProducer);
    final var cc = ClassCreator
        .builder()
        .classOutput(beanClassOutput)
        .className(className)
        .signature(SignatureBuilder
            .forClass()
            .setSuperClass(
                parameterizedType(classType(ProcessServiceBaseCdiBean.class), classType(workflowAggregateType.name()))))
        .build();

    // @ApplicationScoped
    cc.addAnnotation(ApplicationScoped.class);

    /*
     * Class<AggregatePersistenceAware<A>> getAggregatePersistenceClass()
     */
    final var getAggregatePersistenceClass = cc.getMethodCreator(
        "getAggregatePersistenceClass",
        Class.class
    );
    // return AggregatePersistence.class;
    getAggregatePersistenceClass
        .returnValue(getAggregatePersistenceClass.loadClass(aggregatePersistenceClassName));

    /*
     * Class<A> getWorkflowAggregateClass()
     */
    final var getAggregateClass = cc.getMethodCreator(
        "getWorkflowAggregateClass",
        Class.class
    );
    // return A.class;
    getAggregateClass.returnValue(
        getAggregateClass.loadClass(aggregateClassName));

    /*
     * String getWorkflowModuleId()
     */
    final var getWorkflowModuleId = cc.getMethodCreator(
        "getWorkflowModuleId",
        String.class);
    // return "wmid";
    getWorkflowModuleId.returnValue(
        getWorkflowModuleId.load(workflowModuleId));

    /*
     * String getBpmnProcessId()
     */
    final var getBpmnProcessId = cc.getMethodCreator(
        "getBpmnProcessId",
        String.class);
    // return "pid";
    getBpmnProcessId.returnValue(
        getBpmnProcessId.load(bpmnProcessId));

    cc.close();

  }

}
