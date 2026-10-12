package io.vanillabp.integration.deployment.processservice;

import static io.quarkus.gizmo.Type.classType;
import static io.quarkus.gizmo.Type.parameterizedType;

import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.Type;

import io.quarkus.arc.Unremovable;
import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanGizmoAdaptor;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.MethodDescriptor;
import io.quarkus.gizmo.SignatureBuilder;
import io.vanillabp.integration.adapter.migration.workflowtask.AProcessBelongsToOneAggregate;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowServiceBelongsOnAClass;
import io.vanillabp.integration.deployment.config.MigrationAdapterPropertiesBuildItem;
import io.vanillabp.integration.deployment.validation.EnsureClassIsBeanValidationBuildItem;
import io.vanillabp.integration.deployment.workflowmodule.VanillaBpWorkflowModulesBuildItem;
import io.vanillabp.integration.runtime.processservice.ProcessServiceBaseCdiBean;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Build step processor of the VanillaBP extension. It builds the {@link ProcessService}
 * beans.
 * <p>
 * One bean for each workflow aggregate is written as bytecode while the application is
 * built. That is when Quarkus fixes its set of beans. An application which injects
 * <code>ProcessService&lt;TheAggregate&gt;</code> only finds a bean where the build wrote
 * one. At the same time it is decided which persistence that bean uses, and which classes a
 * native image keeps for reflection. Both are read from the Jandex index. Once the
 * application is packaged, neither question can be asked again.
 */
@Slf4j
public class ProcessServiceBuildStepProcessor {

  /**
   * The name of the {@link WorkflowService} attribute naming the workflow aggregate class.
   * <p>
   * The attributes are read from the Jandex index by name. So if one is renamed in the SPI,
   * the compiler does not notice it here. The steps below would simply find nothing.
   * {@code WorkflowServiceAnnotationTest} guards against this. It asks the annotation for a
   * member of this name.
   */
  public static final String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_AGGREGATECLASS = "workflowAggregateClass";

  /** The attribute holding the primary <code>&#64;BpmnProcess</code> of a class. */
  public static final String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS = "bpmnProcess";

  /** The attribute of <code>&#64;BpmnProcess</code> holding the BPMN process ID. */
  public static final String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS_BPMNPROCESSID = "bpmnProcessId";

  /**
   * The attribute holding the further BPMN processes which one class serves. Each of them is
   * a <code>&#64;BpmnProcess</code> of its own.
   */
  public static final String ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_SECONDARYBPMNPROCESSES = "secondaryBpmnProcesses";

  /**
   * Quarkus builds this processor while it augments the application and calls the build
   * steps below on it. Nothing else builds it, and no step keeps state in it.
   */
  public ProcessServiceBuildStepProcessor() {
  }

  /**
   * Application code does not always inject beans which implement
   * {@link AggregatePersistenceAware}. They are looked up at runtime (see
   * {@link ProcessServiceBaseCdiBean}). This build step stops ArC from removing them as
   * unused beans.
   *
   * @return The unremovable-bean build item covering all {@link AggregatePersistenceAware} beans
   */
  @BuildStep
  UnremovableBeanBuildItem preserveAggregatePersistenceAwareBeans() {

    return UnremovableBeanBuildItem.beanTypes(AggregatePersistenceAware.class);

  }

  /**
   * Builds the {@link ProcessService} beans for all services annotated with
   * {@link WorkflowService} at class level.
   *
   * @param applicationArchivesBuildItem Information about All archives (JARs and directories) of the project
   * @param combinedIndex The index of the application and of all indexed dependencies, used to
   *        determine the persistence idiom of an aggregate the application provides no
   *        {@link AggregatePersistenceAware} for
   * @param migrationAdapterProperties Properties of the migration adapter previously built and validated as a dependency
   * @param workflowModulesFound Information about all workflow modules found in the project
   * @param ensureClassIsBeanBuildItemProducer {@link BuildProducer} used to collect the
   *        classes which have to be CDI beans at runtime: the workflow services and the
   *        aggregate persistence
   * @param generatedBeanBuildItemBuildProducer {@link BuildProducer} used to collect generated {@link ProcessService} beans
   * @param reflectiveClassBuildItemProducer {@link BuildProducer} used to register the classes VanillaBP scans by reflection: the workflow services and the aggregates whose ID it reads
   * @param unremovableBeanBuildItemProducer {@link BuildProducer} used to keep repositories alive which only VanillaBP uses
   * @param additionalBeanBuildItemBuildProducer {@link BuildProducer} used to collect beans provided in module "runtime"
   * @param workflowAggregatesProducer {@link BuildProducer} publishing the aggregates found,
   *        so an extension's own beans are built for the same ones without a second scan
   */
  @BuildStep
  void buildProcessServices(
      final ApplicationArchivesBuildItem applicationArchivesBuildItem,
      final CombinedIndexBuildItem combinedIndex,
      final MigrationAdapterPropertiesBuildItem migrationAdapterProperties,
      final VanillaBpWorkflowModulesBuildItem workflowModulesFound,
      final BuildProducer<EnsureClassIsBeanValidationBuildItem> ensureClassIsBeanBuildItemProducer,
      final BuildProducer<GeneratedBeanBuildItem> generatedBeanBuildItemBuildProducer,
      final BuildProducer<ReflectiveClassBuildItem> reflectiveClassBuildItemProducer,
      final BuildProducer<UnremovableBeanBuildItem> unremovableBeanBuildItemProducer,
      final BuildProducer<AdditionalBeanBuildItem> additionalBeanBuildItemBuildProducer,
      final BuildProducer<VanillaBpWorkflowAggregatesBuildItem> workflowAggregatesProducer) {

    final var aggregatePersistenceAwares = applicationArchivesBuildItem
        .getAllArchives()
        .stream()
        .flatMap(archive -> archive
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
    // "First class found" needs a stable archive order to be reproducible, which is
    // why getAllArchives is used: it lists the root archive first and the remaining
    // archives in the order Quarkus resolved them.
    final var workflowServiceAnnotations = applicationArchivesBuildItem
        .getAllArchives()
        .stream()
        .flatMap(archive -> archive
            .getIndex()
            .getAnnotations(WorkflowService.class)
            .stream())
        .toList();

    // an annotated type which is no class serves nothing. This is the place where that is
    // still visible: Jandex reports the type the annotation SITS on. The build ends here,
    // before the ensure-is-bean item below turns the defect into a question about CDI
    // beans
    refuseAnnotationsNotOnAClass(combinedIndex.getIndex(), workflowServiceAnnotations);

    // an annotated class is a DECLARATION. @Inherited says that every subclass of it is a
    // workflow service of its own. Jandex reports where the declarations are. So the
    // classes which will serve the processes are found here, while the index can still be
    // asked who extends whom
    final var workflowServices = workflowServiceClassesOf(combinedIndex.getIndex(), workflowServiceAnnotations);

    // a BPMN process belongs to exactly one workflow aggregate per workflow module, see
    // decision 125 in the repository's DECISIONS.md. This is checked here, while building,
    // with the same text which Spring Boot uses while it starts
    AProcessBelongsToOneAggregate.refuseProcessesOfSeveralAggregates(workflowServices
        .stream()
        .flatMap(service -> {
          final var workflowModuleId = workflowModulesFound
              .getWorkflowModuleId(
                  applicationArchivesBuildItem,
                  service.serviceClass());
          final var aggregateClass = service
              .annotation()
              .value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_AGGREGATECLASS)
              .asClass()
              .name()
              .toString();
          final var primaryProcessId = primaryBpmnProcessId(service.annotation(), service.serviceClass());
          return declaredBpmnProcessIds(service.annotation(), service.serviceClass())
              .stream()
              .map(declaredProcessId -> new AProcessBelongsToOneAggregate.Declaration(
                  workflowModuleId, declaredProcessId, service
                      .serviceClass()
                      .name()
                      .toString(), aggregateClass, declaredProcessId.equals(primaryProcessId)));
        })
        .toList());

    final var servicesByAggregate = new LinkedHashMap<Type, List<WorkflowServiceClass>>();
    workflowServices
        .forEach(service -> servicesByAggregate
            .computeIfAbsent(
                service
                    .annotation()
                    .value(ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_AGGREGATECLASS)
                    .asClass(),
                aggregateType -> new LinkedList<>())
            .add(service));

    servicesByAggregate
        // and build an adapter-aware process service for each workflow aggregate class
        .forEach((
            workflowAggregateType,
            services) -> {

          // record every class of this aggregate under all BPMN process IDs it declares,
          // and make sure that each class is a CDI bean at runtime
          final var workflowTaskRegistrations = new LinkedList<String>();
          for (final var service : services) {
            final var servingClass = service.serviceClass();
            ensureClassIsBeanBuildItemProducer
                .produce(EnsureClassIsBeanValidationBuildItem
                    .builder()
                    .className(servingClass.name())
                    .usageDescription("Workflow service annotated with @"
                        + WorkflowService.class.getName())
                    // a bean of a subclass is a workflow service of its own here. Where the
                    // process ID follows the class name, it even serves a BPMN process of
                    // its own. So it cannot stand in for this class
                    .aBeanOfASubclassCounts(false)
                    .remedy(notABeanRemedy(service))
                    .build());
            // the core finds the @WorkflowTask, @WorkflowStartedByBpms and @WorkflowEnded
            // methods by scanning the class at RUNTIME. A native image only reflects on
            // what it was told about. Without this, every BPMN process is reported as not
            // fully wired at startup, although the methods are right there. The
            // superclasses are registered too, because an inherited handler is declared
            // there and nowhere else
            declarationChainOf(combinedIndex.getIndex(), service)
                .forEach(scannedClass -> reflectiveClassBuildItemProducer
                    .produce(ReflectiveClassBuildItem
                        .builder(scannedClass.toString())
                        .methods()
                        .reason("VanillaBP scans the workflow service's methods by reflection")
                        .build()));
            final var moduleOfServingClass = workflowModulesFound
                .getWorkflowModuleId(
                    applicationArchivesBuildItem,
                    servingClass);
            for (final var declaredProcessId : declaredBpmnProcessIds(service.annotation(), servingClass)) {
              workflowTaskRegistrations.add(
                  "%s|%s|%s".formatted(moduleOfServingClass, servingClass.name(), declaredProcessId));
            }
          }

          // the SPI's injection contract is ONE ProcessService per aggregate. So ONE of
          // the classes declares the process which startWorkflow starts. It used to be
          // whichever class was found first. Several classes may declare the SAME
          // process (handlers split across classes). Different processes are ambiguous
          // and end the build.
          final var primaryService = primaryWorkflowService(services, workflowAggregateType);
          final var annotation = primaryService.annotation();
          final var serviceClass = primaryService.serviceClass();

          final var workflowModuleId = workflowModulesFound
              .getWorkflowModuleId(
                  applicationArchivesBuildItem,
                  serviceClass);
          final var bpmnProcessId = primaryBpmnProcessId(annotation, serviceClass);

          // find the persistence class for the aggregate class. An implementation of the
          // application always wins, and among those the most specific one
          final var applicationPersistenceType = aggregatePersistenceAwares
              .stream()
              .map(awareEntry -> Map.entry(
                  awareEntry.getKey(),
                  AggregatePersistenceResolver.distance(
                      awareEntry.getValue().getIndex(),
                      awareEntry.getKey(),
                      workflowAggregateType.name()
                  )))
              // drop the persistence awares whose aggregate type does not fit this aggregate
              .filter(awareEntry -> awareEntry.getValue() != Integer.MAX_VALUE)
              // choose the most specific persistence support in terms of inheritance class distance
              .min(Comparator.comparingInt(Map.Entry::getValue))
              .map(Map.Entry::getKey);

          final String aggregatePersistenceClassName;
          if (applicationPersistenceType.isPresent()) {
            // make sure the persistence class is a CDI bean at runtime
            ensureClassIsBeanBuildItemProducer
                .produce(EnsureClassIsBeanValidationBuildItem
                    .builder()
                    .className(applicationPersistenceType
                        .get()
                        .name())
                    .usageDescription("Service implementing the interface "
                        + AggregatePersistenceAware.class.getName())
                    .build());
            aggregatePersistenceClassName = applicationPersistenceType
                .get()
                .name()
                .toString();
          } else {
            // the application has no implementation. Use the one which matches the
            // persistence idiom of the aggregate
            final var defaultPersistence = DefaultAggregatePersistenceResolver
                .resolve(combinedIndex.getIndex(), workflowAggregateType.name())
                .orElseThrow(() -> new IllegalStateException(
                    missingAggregatePersistenceMessage(workflowAggregateType, workflowModuleId)));
            log.info(
                "Using VanillaBP's {} persistence for workflow aggregate '{}'{}",
                defaultPersistence.idiom(),
                workflowAggregateType.name(),
                defaultPersistence.repositoryClass() == null
                    ? ""
                    : " (repository '%s')".formatted(defaultPersistence.repositoryClass()));
            aggregatePersistenceClassName = "%s.AggregatePersistence_%s".formatted(
                workflowAggregateType
                    .name()
                    .packagePrefix(),
                workflowAggregateType
                    .name()
                    .withoutPackagePrefix());
            generateDefaultAggregatePersistence(
                generatedBeanBuildItemBuildProducer,
                aggregatePersistenceClassName,
                defaultPersistence,
                workflowAggregateType);
            if (defaultPersistence.repositoryClass() != null) {
              // if only VanillaBP uses a repository, the application injects it nowhere.
              // Quarkus removes beans nobody injects while it builds the application
              // (the same case as the multi-instance resolvers). Today Panache and
              // Spring Data keep their repositories themselves. So this is insurance,
              // not a fix. VanillaBP looks the bean up by class and should not depend
              // on the decision of another extension
              unremovableBeanBuildItemProducer
                  .produce(UnremovableBeanBuildItem.beanTypes(defaultPersistence.repositoryClass()));
            }
            // the ID is read by reflection (AggregateIdTypes), so a native image has to
            // be told about it. The persistence frameworks register their entities
            // themselves, but an aggregate may not be such an entity
            reflectiveClassBuildItemProducer
                .produce(ReflectiveClassBuildItem
                    .builder(workflowAggregateType
                        .name()
                        .toString())
                    .fields()
                    .methods()
                    .reason("VanillaBP reads the workflow aggregate's ID by reflection")
                    .build());
          }

          // generate process service CDI bean specific to the workflow aggregate
          generateProcessService(
              generatedBeanBuildItemBuildProducer,
              workflowModuleId,
              bpmnProcessId,
              "%s.ProcessService_%s".formatted(
                  workflowAggregateType.name().packagePrefix(),
                  workflowAggregateType.name().withoutPackagePrefix()),
              aggregatePersistenceClassName,
              workflowAggregateType,
              String.join(";", workflowTaskRegistrations));

        });

    // an extension's service for each aggregate is built for the same aggregates. So they
    // are published here, and nobody has to scan for them a second time
    workflowAggregatesProducer
        .produce(new VanillaBpWorkflowAggregatesBuildItem(servicesByAggregate
            .keySet()
            .stream()
            .map(Type::name)
            .toList()));

  }

  /**
   * One class which IS a workflow service, together with the declaration which makes it one.
   * That is either its own <code>&#64;WorkflowService</code> or the one it inherited from a
   * superclass.
   *
   * @param serviceClass The class serving the BPMN processes of the declaration
   * @param annotation The declaration, whose attributes {@link java.lang.annotation.Inherited}
   *        answers on the class as well
   * @param declaringClass The class the declaration sits on, equal to
   *        <code>serviceClass</code> wherever the class carries the annotation itself
   */
  private record WorkflowServiceClass(
                                      ClassInfo serviceClass,
                                      AnnotationInstance annotation,
                                      ClassInfo declaringClass) {
  }

  /**
   * The classes which serve the BPMN processes of the declarations found. This is what
   * <code>&#64;Inherited</code> promises the developer. A subclass of an annotated class is a
   * workflow service, and the handler methods are read from the SUBCLASS. Jandex does not
   * resolve <code>&#64;Inherited</code>. So the subclasses are looked up here, where the index
   * can still be asked. Spring Boot gets the same result, because it registers the class of
   * the bean (see decision 32 in the repository's DECISIONS.md).
   * <p>
   * Abstract classes are left out. VanillaBP asks CDI for an instance of a workflow service.
   * So a class which nobody can instantiate serves nothing. If a declaration and all its
   * subclasses are abstract, nobody serves it. Then the build ends and names it.
   *
   * @param index The index of the application and its indexed dependencies
   * @param annotations Every <code>&#64;WorkflowService</code> of the application archives
   * @return The workflow service classes, each of them once
   * @throws IllegalStateException If a declaration has no class which could serve it
   */
  private static List<WorkflowServiceClass> workflowServiceClassesOf(
      final IndexView index,
      final List<AnnotationInstance> annotations) {

    final var byClassName = new LinkedHashMap<DotName, WorkflowServiceClass>();
    final var declarationsNobodyServes = new LinkedHashMap<DotName, ClassInfo>();
    for (final var annotation : annotations) {
      final var declaringClass = annotation.target().asClass();
      final var serviceClasses = new LinkedList<ClassInfo>();
      if (!Modifier.isAbstract(declaringClass.flags())) {
        serviceClasses.add(declaringClass);
      }
      index
          .getAllKnownSubclasses(declaringClass.name())
          .stream()
          .filter(subclass -> !Modifier.isAbstract(subclass.flags()))
          .sorted(Comparator.comparing(subclass -> subclass.name().toString()))
          .forEach(serviceClasses::add);
      if (serviceClasses.isEmpty()) {
        declarationsNobodyServes.putIfAbsent(declaringClass.name(), declaringClass);
        continue;
      }
      for (final var serviceClass : serviceClasses) {
        final var known = byClassName.get(serviceClass.name());
        // a class inherits the annotation of its NEAREST annotated superclass. Its own
        // annotation (distance zero) replaces everything it inherited
        if ((known == null) || (inheritanceDistance(index, serviceClass,
            declaringClass.name()) < inheritanceDistance(index, serviceClass, known.declaringClass().name()))) {
          byClassName.put(
              serviceClass.name(),
              new WorkflowServiceClass(serviceClass, annotation, declaringClass));
        }
      }
    }
    if (!declarationsNobodyServes.isEmpty()) {
      throw new IllegalStateException(declarationsNobodyServes
          .values()
          .stream()
          .map(ProcessServiceBuildStepProcessor::declarationNobodyServesMessage)
          .collect(java.util.stream.Collectors.joining("\n")));
    }
    return List.copyOf(byClassName.values());

  }

  /**
   * How many superclasses lie between a class and the class which carries the declaration it
   * serves. It is zero where the class carries the declaration itself. It is
   * {@link Integer#MAX_VALUE} where the index does not connect the two.
   */
  private static int inheritanceDistance(
      final IndexView index,
      final ClassInfo serviceClass,
      final DotName declaringClassName) {

    var distance = 0;
    var current = serviceClass;
    while (current != null) {
      if (current.name().equals(declaringClassName)) {
        return distance;
      }
      final var superClass = current.superClassType();
      if (superClass == null) {
        return Integer.MAX_VALUE;
      }
      current = index.getClassByName(superClass.name());
      distance++;
    }
    return Integer.MAX_VALUE;

  }

  /**
   * The class and every superclass up to the one which carries the declaration. The handler
   * methods of a workflow service may be declared in any of them. And a native image only
   * reflects on what it was told about.
   */
  private static List<DotName> declarationChainOf(
      final IndexView index,
      final WorkflowServiceClass service) {

    final var chain = new LinkedList<DotName>();
    var current = service.serviceClass();
    while (current != null) {
      chain.add(current.name());
      if (current.name().equals(service.declaringClass().name())) {
        break;
      }
      final var superClass = current.superClassType();
      current = superClass == null
          ? null
          : index.getClassByName(superClass.name());
    }
    return chain;

  }

  /**
   * What to do about a workflow service class which is no CDI bean. The advice differs for a
   * class which inherits its declaration. There the developer may not have meant this class
   * to be a workflow service at all.
   */
  private static String notABeanRemedy(
      final WorkflowServiceClass service) {

    if (service.serviceClass().name().equals(service.declaringClass().name())) {
      return "Please annotate it with a bean-defining annotation such as @ApplicationScoped.";
    }
    return """
        It inherits @WorkflowService from
          %s
        and @WorkflowService is @Inherited, so this class is a workflow service of its own and \
        VanillaBP asks CDI for an instance of THIS class. Either annotate it with a \
        bean-defining annotation such as @ApplicationScoped, or, where it is meant to carry the \
        declaration only, make it abstract. Where it is no workflow service at all, let it \
        extend a class which does not carry the annotation."""
        .formatted(service.declaringClass().name());

  }

  /**
   * The message ending the build where a declaration has no class which could serve it.
   */
  private static String declarationNobodyServesMessage(
      final ClassInfo declaringClass) {

    return """
        @WorkflowService sits on the class
          %s
        which is abstract, and no class of this application extends it.
        VanillaBP reads the handler methods off the workflow service class and asks CDI for an \
        instance of it, so an abstract declaration reaches a BPMN process through a subclass \
        only: @WorkflowService is @Inherited, which makes every subclass of an annotated class a \
        workflow service of its own. Either write that subclass and make it a CDI bean, or move \
        the annotation onto the class holding the handler methods. If the subclass does exist, \
        the build did not see it: classes of a workflow module in its own Maven module have to be \
        indexed to be seen (see the jandex-maven-plugin)."""
        .formatted(declaringClass.name());

  }

  /**
   * Ends the build where <code>&#64;WorkflowService</code> does not sit on a class. It may sit
   * on an interface, or on an annotation of the application which uses it as a
   * meta-annotation. Jandex does not resolve meta-annotations. It reports the annotation type
   * itself as the annotated type. So both cases arrive here as an annotated INTERFACE. All of
   * them are reported, so a developer does not meet the second one on the next build.
   *
   * @param index The index of the application and its indexed dependencies, asked which
   *        classes brought the declaration in
   * @param annotations Every <code>&#64;WorkflowService</code> of the application archives
   */
  private static void refuseAnnotationsNotOnAClass(
      final IndexView index,
      final List<AnnotationInstance> annotations) {

    final var declaringClasses = new LinkedHashMap<DotName, ClassInfo>();
    annotations
        .stream()
        .filter(annotation -> annotation.target().kind() == AnnotationTarget.Kind.CLASS)
        .map(annotation -> annotation.target().asClass())
        .filter(ClassInfo::isInterface)
        .forEach(declaringClass -> declaringClasses.putIfAbsent(declaringClass.name(), declaringClass));
    if (declaringClasses.isEmpty()) {
      return;
    }
    throw new IllegalStateException(declaringClasses
        .values()
        .stream()
        .map(declaringClass -> declaringClass.isAnnotation()
            ? WorkflowServiceBelongsOnAClass.foundOnAnAnnotation(
                declaringClass.name().toString(),
                classNamesOf(index
                    .getAnnotations(declaringClass.name())
                    .stream()
                    .filter(usage -> usage.target().kind() == AnnotationTarget.Kind.CLASS)
                    .map(usage -> usage.target().asClass())
                    .toList()))
            : WorkflowServiceBelongsOnAClass.foundOnAnInterface(
                declaringClass.name().toString(),
                classNamesOf(index.getAllKnownImplementors(declaringClass.name()))))
        .collect(java.util.stream.Collectors.joining("\n")));

  }

  /**
   * The names of the classes which brought a declaration in. They are sorted, so the message
   * does not depend on the order in which the archives were scanned.
   */
  private static List<String> classNamesOf(
      final Collection<ClassInfo> classes) {

    return classes
        .stream()
        .map(clazz -> clazz.name().toString())
        .sorted()
        .toList();

  }

  /**
   * The message shown when neither the application nor one of the persistence idioms which
   * VanillaBP knows handles an aggregate. It names what was looked for. "Provide a bean"
   * alone would leave the reader guessing which of the Quarkus ways to use.
   */
  private static String missingAggregatePersistenceMessage(
      final Type workflowAggregateType,
      final String workflowModuleId) {

    // the message names the workflow module, the same way Spring Boot reports this
    // defect. An application with several modules should not have to guess which one the
    // aggregate belongs to. And the two platforms report the same thing in the same words
    return """
        VanillaBP does not know how to persist the workflow aggregate '%s' of workflow module '%s'!
        Either the aggregate uses one of the persistence idioms VanillaBP serves out of the box:
        - a Panache repository for the aggregate (PanacheRepository/PanacheRepositoryBase, or \
        PanacheMongoRepository/PanacheMongoRepositoryBase): https://quarkus.io/guides/hibernate-orm-panache#solution-2-using-the-repository-pattern
        - the aggregate itself being a Panache active record (extending PanacheEntity/PanacheEntityBase, or \
        PanacheMongoEntity/PanacheMongoEntityBase): https://quarkus.io/guides/hibernate-orm-panache#solution-1-using-the-active-record-pattern
        - a Spring Data repository for the aggregate (extension quarkus-spring-data-jpa): https://quarkus.io/guides/spring-data-jpa
        None of them was found for this aggregate (mind that classes of workflow modules have to be \
        indexed using the jandex-maven-plugin to be seen).
        Or, for any other kind of persistence, provide a CDI bean implementing
          %s<%s>
        which is responsible to persist this aggregate."""
        .formatted(
            workflowAggregateType.name(),
            workflowModuleId,
            AggregatePersistenceAware.class.getName(),
            workflowAggregateType
                .name()
                .withoutPackagePrefix());

  }

  /**
   * Generates the CDI bean which provides one of VanillaBP's persistence implementations
   * for a specific aggregate:
   *
   * <pre>
   * &#64;Singleton
   * public class AggregatePersistence_Aggregate extends PanacheRepositoryAggregatePersistence&lt;Aggregate&gt; {
   *   public AggregatePersistence_Aggregate() {
   *     super(Aggregate.class, AggregateRepository.class);
   *   }
   * }
   * </pre>
   *
   * The generic superclass is spelled out (not raw) on purpose. The bean type has to be
   * {@code AggregatePersistenceAware<Aggregate>}, so the runtime lookup of
   * {@link ProcessServiceBaseCdiBean} finds it.
   *
   * @param className The class name of the bean to build
   * @param defaultPersistence The implementation chosen for the aggregate
   * @param workflowAggregateType The workflow aggregate type the persistence is for
   */
  private void generateDefaultAggregatePersistence(
      final BuildProducer<GeneratedBeanBuildItem> generatedBeanBuildItemBuildProducer,
      final String className,
      final DefaultAggregatePersistenceResolver.DefaultPersistence defaultPersistence,
      final Type workflowAggregateType) {

    final var beanClassOutput = new GeneratedBeanGizmoAdaptor(generatedBeanBuildItemBuildProducer);
    final var implementationClass = DotName.createSimple(defaultPersistence.implementationClass());
    final var cc = ClassCreator
        .builder()
        .classOutput(beanClassOutput)
        .className(className)
        .signature(SignatureBuilder
            .forClass()
            .setSuperClass(
                parameterizedType(classType(implementationClass), classType(workflowAggregateType.name()))))
        .build();

    cc.addAnnotation(Singleton.class);
    // the bean is never injected but looked up by its class at runtime
    cc.addAnnotation(Unremovable.class);

    final var constructor = cc.getConstructorCreator(new String[0]);
    final var aggregateClass = constructor.loadClass(workflowAggregateType
        .name()
        .toString());
    if (defaultPersistence.repositoryClass() == null) {
      constructor.invokeSpecialMethod(
          MethodDescriptor.ofConstructor(defaultPersistence.implementationClass(), Class.class.getName()),
          constructor.getThis(),
          aggregateClass);
    } else {
      constructor.invokeSpecialMethod(
          MethodDescriptor.ofConstructor(
              defaultPersistence.implementationClass(), Class.class.getName(), Class.class.getName()),
          constructor.getThis(),
          aggregateClass,
          constructor.loadClass(defaultPersistence
              .repositoryClass()
              .toString()));
    }
    constructor.returnVoid();

    cc.close();

  }

  /**
   * The primary BPMN process ID of a workflow service class. It is
   * {@code @WorkflowService.bpmnProcess().bpmnProcessId()}, or by convention the simple
   * name of the class.
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
   * The workflow service class declaring the process of the aggregate's process service.
   *
   * @param services All workflow service classes of this aggregate
   * @param workflowAggregateType The aggregate
   * @return The class whose primary BPMN process the process service serves
   * @throws IllegalStateException If the classes declare different primary processes
   *         for one aggregate. It cannot be decided here which of them {@code startWorkflow}
   *         would start. The adapter reads the BPMN models later, while deploying. So the
   *         application decides it. It names one process as the primary one and the
   *         others as secondary
   */
  private static WorkflowServiceClass primaryWorkflowService(
      final List<WorkflowServiceClass> services,
      final Type workflowAggregateType) {

    // reproducible: with several classes on one process, the choice must not depend on
    // the order in which the archives were scanned
    final var sorted = services
        .stream()
        .sorted(Comparator.comparing(candidate -> candidate
            .serviceClass()
            .name()
            .toString()))
        .toList();
    final var distinctProcesses = sorted
        .stream()
        .map(candidate -> primaryBpmnProcessId(candidate.annotation(), candidate.serviceClass()))
        .distinct()
        .toList();
    if (distinctProcesses.size() > 1) {
      final var declarations = sorted
          .stream()
          .map(candidate -> "  %s declares '%s'".formatted(
              candidate
                  .serviceClass()
                  .name(),
              primaryBpmnProcessId(candidate.annotation(), candidate.serviceClass())))
          .collect(java.util.stream.Collectors.joining("\n"));
      throw new IllegalStateException(
          """
              Several workflow service classes declare a DIFFERENT BPMN process for the workflow \
              aggregate '%s':
              %s
              VanillaBP provides one ProcessService per workflow aggregate (that is what \
              'ProcessService<%s>' injects), so exactly one of these processes is the one \
              'startWorkflow' starts - and picking one of them here would be a coin flip.
              Declare the process to be started as the 'bpmnProcess' of ONE class and move the \
              others into that class' 'secondaryBpmnProcesses' (a process called by a call \
              activity is the typical case). Handlers of a secondary process may stay in their own \
              class as long as that class declares the same 'bpmnProcess'.
              A class inheriting @WorkflowService from a superclass is one of these classes too, \
              and without an explicit 'bpmnProcess' each of them names its process after ITSELF - \
              so several subclasses of one annotated base need that 'bpmnProcess' on the base."""
              .formatted(
                  workflowAggregateType.name(),
                  declarations,
                  workflowAggregateType
                      .name()
                      .withoutPackagePrefix()));
    }
    return sorted.getFirst();

  }

  /**
   * All BPMN process IDs which a workflow service class declares: the primary
   * {@code bpmnProcess} plus every {@code secondaryBpmnProcesses} entry. Secondary entries
   * have to be explicit. There is no class-name convention for them.
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
   * Generates the process service CDI bean for the given workflow aggregate type.
   *
   * @param generatedBeanBuildItemBuildProducer The producer used to build multiple beans if necessary
   * @param workflowModuleId The ID of the workflow module the service belongs to
   * @param bpmnProcessId The BPMN process ID the service is for
   * @param className The class name of the service to build
   * @param aggregatePersistenceClassName The aggregate persistence class to be used by the service
   * @param workflowAggregateType The workflow aggregate type the service is for
   */
  private void generateProcessService(
      final BuildProducer<GeneratedBeanBuildItem> generatedBeanBuildItemBuildProducer,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String className,
      final String aggregatePersistenceClassName,
      final Type workflowAggregateType,
      final String workflowTaskRegistrations) {

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
    // @Unremovable: the bean is injected by injection points for one aggregate class
    // (e.g. "ProcessService<MyAggregate>"). ArC's removal detection does not always see
    // them (e.g. if the bean is only looked up in code)
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
