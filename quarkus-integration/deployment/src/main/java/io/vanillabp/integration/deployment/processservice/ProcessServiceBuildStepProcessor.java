package io.vanillabp.integration.deployment.processservice;

import static io.quarkus.gizmo.Type.classType;
import static io.quarkus.gizmo.Type.parameterizedType;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.Type;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanGizmoAdaptor;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.SignatureBuilder;
import io.vanillabp.integration.deployment.config.MigrationAdapterPropertiesBuildItem;
import io.vanillabp.integration.deployment.validation.EnsureClassIsBeanValidationBuildItem;
import io.vanillabp.integration.deployment.workflowmodule.VanillaBpWorkflowModulesBuildItem;
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

  public static final String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_AGGREGATECLASS = "workflowAggregateClass";
  public static final String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS = "bpmnProcess";
  public static final String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS_BPMNPROCESSID = "bpmnProcessId";

  public static final String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_SECONDARYBPMNPROCESSES = "secondaryBpmnProcesses";

  /**
   * Beans implementing {@link AggregatePersistenceAware} are not necessarily injected by
   * application code but looked up dynamically at runtime (see
   * {@link ProcessServiceBaseCdiBean}). This build step prevents ArC from removing them
   * as unused beans.
   *
   * @return The unremovable-bean build item covering all {@link AggregatePersistenceAware} beans
   */
  @BuildStep
  UnremovableBeanBuildItem preserveAggregatePersistenceAwareBeans() {

    return UnremovableBeanBuildItem.beanTypes(AggregatePersistenceAware.class);

  }

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

    // scan for classes annotated by @WorkflowService and group them by aggregate
    // type: ONE injectable ProcessService bean per aggregate (the SPI's injection
    // contract), whose primary BPMN process is the first class found declaring the
    // aggregate. ALL classes declaring the aggregate and ALL their declared BPMN
    // process IDs (bpmnProcess + secondaryBpmnProcesses) are recorded for
    // phase-two routing and @WorkflowTask processing at runtime.
    final var annotationsByAggregate = new LinkedHashMap<Type, List<AnnotationInstance>>();
    applicationArchivesBuildItem
        // search all archives of the project
        .getAllApplicationArchives()
        .stream()
        .flatMap(archive -> archive
            .getIndex()
            .getAnnotations(WorkflowService.class)
            .stream())
        .forEach(annotation -> annotationsByAggregate
            .computeIfAbsent(
                annotation
                    .value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_AGGREGATECLASS)
                    .asClass(),
                aggregateType -> new LinkedList<>())
            .add(annotation));

    annotationsByAggregate
        // and build an adapter-aware process service for each workflow aggregate class
        .forEach((
            workflowAggregateType,
            annotations) -> {

          // record every class of this aggregate under all its declared BPMN
          // process IDs and make sure each class is a CDI bean at runtime
          final var workflowTaskRegistrations = new LinkedList<String>();
          for (final var declaringAnnotation : annotations) {
            final var declaringClass = declaringAnnotation.target().asClass();
            ensureClassIsBeanBuildItemProducer
                .produce(EnsureClassIsBeanValidationBuildItem
                    .builder()
                    .className(declaringClass.name())
                    .usageDescription("Workflow service annotated with @"
                        + WorkflowService.class.getName())
                    .build());
            final var declaringModuleId = workflowModulesFound
                .getWorkflowModuleId(
                    applicationArchivesBuildItem,
                    declaringClass);
            for (final var declaredProcessId : declaredBpmnProcessIds(declaringAnnotation, declaringClass)) {
              workflowTaskRegistrations.add(
                  "%s|%s|%s".formatted(declaringModuleId, declaringClass.name(), declaredProcessId));
            }
          }

          // collect information necessary for bean creation (first class found
          // provides the primary BPMN process and the bean's identity)
          final var annotation = annotations.getFirst();
          final var serviceClass = annotation.target().asClass();

          final var workflowModuleId = workflowModulesFound
              .getWorkflowModuleId(
                  applicationArchivesBuildItem,
                  serviceClass);
          final var bpmnProcessId = primaryBpmnProcessId(annotation, serviceClass);

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
              workflowAggregateType,
              String.join(";", workflowTaskRegistrations));

        });

  }

  /**
   * The primary BPMN process ID of a workflow service class:
   * {@code @WorkflowService.bpmnProcess().bpmnProcessId()} or, by convention, the
   * class' simple name.
   */
  private static String primaryBpmnProcessId(
      final AnnotationInstance annotation,
      final ClassInfo serviceClass) {

    return Optional
        .ofNullable(annotation.value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS))
        .map(AnnotationValue::asNested)
        .map(a -> a.value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS_BPMNPROCESSID))
        .map(AnnotationValue::asString)
        .filter(Predicate.not(String::isEmpty))
        .orElse(serviceClass.simpleName());

  }

  /**
   * All BPMN process IDs a workflow service class declares: the primary
   * {@code bpmnProcess} plus every {@code secondaryBpmnProcesses} entry. Secondary
   * entries have to be explicit - there is no class-name convention for them.
   */
  private static List<String> declaredBpmnProcessIds(
      final AnnotationInstance annotation,
      final ClassInfo serviceClass) {

    final var bpmnProcessIds = new LinkedList<String>();
    bpmnProcessIds.add(primaryBpmnProcessId(annotation, serviceClass));
    final var secondaries = annotation.value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_SECONDARYBPMNPROCESSES);
    if (secondaries != null) {
      for (final var secondary : secondaries.asNestedArray()) {
        final var secondaryProcessId = Optional
            .ofNullable(secondary.value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS_BPMNPROCESSID))
            .map(AnnotationValue::asString)
            .filter(Predicate.not(String::isEmpty))
            .orElseThrow(() -> new IllegalStateException(
                """
                    A secondaryBpmnProcesses entry of @WorkflowService at '%s' has no bpmnProcessId! \
                    Secondary BPMN processes have to be declared explicitly, e.g. \
                    @BpmnProcess(bpmnProcessId = "MyOtherProcess")."""
                    .formatted(serviceClass.name())));
        bpmnProcessIds.add(secondaryProcessId);
      }
    }
    return bpmnProcessIds;

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
      final Type workflowAggregateType,
      final String workflowTaskRegistrations) {

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
    // @Unremovable: the bean is injected by aggregate class specific injection points
    // (e.g. "ProcessService<MyAggregate>") which ArC's removal detection does not
    // recognize in all situations (e.g. if only accessed programmatically)
    cc.addAnnotation(Unremovable.class);

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

    /*
     * String getWorkflowTaskRegistrations()
     */
    final var getWorkflowTaskRegistrations = cc.getMethodCreator(
        "getWorkflowTaskRegistrations",
        String.class);
    // return "module|class|pid;...";
    getWorkflowTaskRegistrations.returnValue(
        getWorkflowTaskRegistrations.load(workflowTaskRegistrations));

    cc.close();

  }

}
