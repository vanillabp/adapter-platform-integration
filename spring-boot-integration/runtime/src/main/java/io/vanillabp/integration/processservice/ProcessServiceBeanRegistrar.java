package io.vanillabp.integration.processservice;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.utils.ClasspathScanner;
import io.vanillabp.integration.utils.SpringDataUtil;
import io.vanillabp.integration.utils.impl.SpringDataUtilBasedAggregatePersistenceSupport;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.MultiInstanceElementResolver;
import io.vanillabp.spi.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;

/**
 * A Spring Framework {@link BeanRegistrar} building
 * {@link io.vanillabp.spi.process.ProcessService} beans for each aggregate type of
 * workflow services found in classpath. It is imported by
 * {@link SpringBootMigrationAdapterAutoConfiguration}.
 * <p>
 * At registration time only the classpath is scanned — no beans are touched. Each
 * bean is registered with a generics-aware target type
 * ({@code ProcessService<WorkflowAggregate>}), so generic autowiring works, and with
 * a lazy supplier: all dependencies (properties, persistence support, adapter
 * process services) are resolved through the
 * {@link BeanRegistry.SupplierContext} at bean-creation time. This way neither
 * Hibernate/DataSource nor adapter beans are materialized during the bean-factory
 * post-processing phase, keeping AOP proxying and
 * {@code @ConfigurationProperties} binding intact for all beans involved.
 */
@Slf4j
public class ProcessServiceBeanRegistrar implements BeanRegistrar {

  @Override
  public void register(
      final BeanRegistry registry,
      final Environment environment) {

    try {

      // find all workflow service classes in classpath: @WorkflowService is
      // @Inherited, so the superclass chain has to be examined, too (a subclass of
      // an annotated class is itself a workflow service)
      final var metadataReaderFactory = new org.springframework.core.type.classreading.SimpleMetadataReaderFactory();
      final var workflowServiceClasses = new ClasspathScanner()
          .allClasses(
              "",
              metadataReader -> {
                try {
                  var metadata = metadataReader.getAnnotationMetadata();
                  while (true) {
                    if (metadata.hasAnnotation(WorkflowService.class.getName())) {
                      return true;
                    }
                    final var superClassName = metadata.getSuperClassName();
                    if ((superClassName == null) || superClassName.startsWith("java.")) {
                      return false;
                    }
                    metadata = metadataReaderFactory
                        .getMetadataReader(superClassName)
                        .getAnnotationMetadata();
                  }
                } catch (Exception e) {
                  return false;
                }
              }
          );

      // build ProcessService<A> bean definitions: ONE injectable bean per
      // aggregate type (the SPI's injection contract), whose primary BPMN process
      // is the first class found declaring the aggregate. ALL classes declaring
      // the aggregate and ALL their declared BPMN process IDs (bpmnProcess +
      // secondaryBpmnProcesses) are additionally registered for phase-two routing
      // and @WorkflowTask processing.
      final var classesByAggregate = new LinkedHashMap<Class<?>, List<Class<?>>>();
      workflowServiceClasses
          .forEach(serviceClass -> classesByAggregate
              .computeIfAbsent(
                  serviceClass.getAnnotation(WorkflowService.class).workflowAggregateClass(),
                  aggregateType -> new LinkedList<>())
              .add(serviceClass));
      classesByAggregate
          .forEach((
              workflowAggregateType,
              serviceClasses) -> registerProcessServiceBean(
                  registry,
                  workflowServiceClasses,
                  serviceClasses,
                  workflowAggregateType));

    } catch (Exception e) {
      throw new IllegalStateException("Could not register ProcessService beans", e);
    }

  }

  /**
   * The primary BPMN process ID of a workflow service class:
   * {@code @WorkflowService.bpmnProcess().bpmnProcessId()} or, by convention, the
   * class' simple name.
   */
  static String primaryBpmnProcessId(
      final Class<?> serviceClass) {

    return Optional.of(serviceClass
        .getAnnotation(WorkflowService.class)
        .bpmnProcess()
        .bpmnProcessId())
        .filter(Predicate.not(String::isEmpty))
        .orElse(serviceClass.getSimpleName());

  }

  /**
   * All BPMN process IDs a workflow service class declares: the primary
   * {@code bpmnProcess} plus every {@code secondaryBpmnProcesses} entry. Secondary
   * entries have to be explicit - there is no class-name convention for them.
   */
  static List<String> declaredBpmnProcessIds(
      final Class<?> serviceClass) {

    final var annotation = serviceClass.getAnnotation(WorkflowService.class);
    final var bpmnProcessIds = new LinkedList<String>();
    bpmnProcessIds.add(primaryBpmnProcessId(serviceClass));
    for (final var secondary : annotation.secondaryBpmnProcesses()) {
      if (secondary.bpmnProcessId().isEmpty()) {
        throw new IllegalStateException(
            """
                A secondaryBpmnProcesses entry of @WorkflowService at '%s' has no bpmnProcessId! \
                Secondary BPMN processes have to be declared explicitly, e.g. \
                @BpmnProcess(bpmnProcessId = "MyOtherProcess")."""
                .formatted(serviceClass.getName()));
      }
      bpmnProcessIds.add(secondary.bpmnProcessId());
    }
    return bpmnProcessIds;

  }

  /**
   * Registers the bean definition for a single
   * {@link io.vanillabp.spi.process.ProcessService} bean: registering a definition
   * having a lazy supplier (a promise to Spring that this bean will be available)
   * instead of an instance avoids circular dependencies between the class annotated
   * by {@link WorkflowService} and the ProcessService bean.
   */
  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  private <A> void registerProcessServiceBean(
      final BeanRegistry registry,
      final List<Class<?>> allWorkflowServiceClasses,
      final List<Class<?>> serviceClasses,
      final Class<A> workflowAggregateType) {

    final var serviceClass = serviceClasses.getFirst();
    final var bpmnProcessId = primaryBpmnProcessId(serviceClass);

    final var beanType = ParameterizedTypeReference
        .<ProcessService<A>>forType(ResolvableType
            .forClassWithGenerics(ProcessService.class, workflowAggregateType)
            .getType());

    registry.registerBean(
        "VanillaBP_ProcessService_%s".formatted(workflowAggregateType.getName()),
        beanType,
        spec -> spec
            .supplier(supplierContext -> {

              // associate workflow services with workflow modules (done only once;
              // uses the application's resource loader to find the modules)
              final var allWorkflowModules = supplierContext.bean(WorkflowModules.class);
              allWorkflowModules.associateWorkflowServices(allWorkflowServiceClasses);

              final var workflowModuleId = workflowModuleOf(allWorkflowModules, serviceClass);

              final var properties = supplierContext.bean(
                  SpringBootMigrationAdapterAutoConfiguration.BEANNAME_MIGRATIONADAPERPROPERTIES,
                  MigrationAdapterProperties.class);

              // find persistence support for the aggregate class (most specific
              // aggregate class wins - selection shared with the core)
              final List<AggregatePersistenceAware<?>> aggregatePersistenceAwares = supplierContext
                  .beanProvider(AggregatePersistenceAware.class)
                  .stream()
                  .<AggregatePersistenceAware<?>>map(aware -> (AggregatePersistenceAware<?>) aware)
                  .toList();
              final var aggregatePersistenceAware = (AggregatePersistenceAware<A>) AwareSelection
                  .mostSpecific(
                      aggregatePersistenceAwares,
                      AggregatePersistenceAware::getAggregateClass,
                      workflowAggregateType)
                  // if none found, fall back to persistence support based on Spring Data Util bean
                  .orElseGet(() -> {
                    final var springDataUtil = supplierContext
                        .beanProvider(SpringDataUtil.class)
                        .getIfAvailable();
                    if (springDataUtil == null) {
                      throw new IllegalStateException(
                          """
                              Spring Data Util bean not found! To solve this either
                              - add spring-boot-starter-data-jpa to classpath and configure a data source, if you use JPA for persistence of aggregates
                              - add spring-boot-starter-data-mongodb to classpath and configure the MongoDb connection, if you use MongoDb for persistence of aggregates
                              - add your own implementation of io.vanillabp.integration.utils.SpringDataUtil, if you use an alternative persistence""");
                    }
                    return new SpringDataUtilBasedAggregatePersistenceSupport(
                        springDataUtil, workflowAggregateType);
                  });

              final var migratableProcessServices = supplierContext
                  .beanProvider(MigratableProcessService.class)
                  .stream()
                  .map(processService -> (MigratableProcessService<A>) processService)
                  .toList();

              // resolves the outbox per aggregate (mixed persistence, dedicated
              // outboxes) - invoked at startup by the platform's startup
              // validation, never mid-bean-construction
              final var phaseTwoOutboxResolver = supplierContext
                  .bean(SpringPhaseTwoOutboxResolver.class);

              // the bean registers itself with the router as phase-two dispatch
              // target of this workflow module/BPMN process
              final var phaseTwoRouter = supplierContext
                  .bean(PhaseTwoRouter.class);

              // the election cache (in-memory default or the application's own
              // bean, e.g. cluster-shared)
              final var workflowAdapterCache = selectWorkflowAdapterCache(
                  supplierContext
                      .beanProvider(io.vanillabp.integration.spi.WorkflowAdapterCache.class)
                      .stream()
                      .map(io.vanillabp.integration.spi.WorkflowAdapterCache.class::cast)
                      .toList());

              final var processServiceBean = new ProcessServiceSpringBean<A>(
                  workflowModuleId, bpmnProcessId, workflowAggregateType, properties, aggregatePersistenceAware, migratableProcessServices, phaseTwoOutboxResolver, phaseTwoRouter, workflowAdapterCache);

              // register ALL classes declaring this aggregate under ALL their
              // declared BPMN process IDs: @WorkflowTask handlers per (module,
              // process) plus phase-two routing for secondary processes
              final var taskRegistry = supplierContext.bean(WorkflowTaskRegistry.class);
              // resolver beans (@MultiInstanceElement(resolverBean = ...)) are
              // looked up lazily among all MultiInstanceElementResolver beans
              final var resolverBeans = supplierContext.beanProvider(MultiInstanceElementResolver.class);
              final Function<Class<?>, Object> beanResolver = type -> resolverBeans
                  .stream()
                  .filter(type::isInstance)
                  .findFirst()
                  .orElse(null);
              final var processServicesByKey = new HashMap<String, MigrationProcessService<A>>();
              processServicesByKey.put(
                  "%s|%s".formatted(workflowModuleId, bpmnProcessId),
                  processServiceBean.getMigrationProcessService());
              for (final var declaringClass : serviceClasses) {
                final var declaringModuleId = workflowModuleOf(allWorkflowModules, declaringClass);
                for (final var declaredProcessId : declaredBpmnProcessIds(declaringClass)) {
                  final var processService = processServicesByKey.computeIfAbsent(
                      "%s|%s".formatted(declaringModuleId, declaredProcessId),
                      key -> {
                        final var secondaryProcessService = new MigrationProcessService<A>(
                            declaringModuleId, declaredProcessId, workflowAggregateType, properties, aggregatePersistenceAware, migratableProcessServices, phaseTwoOutboxResolver, workflowAdapterCache);
                        if (phaseTwoRouter != null) {
                          phaseTwoRouter.register(secondaryProcessService);
                        }
                        return secondaryProcessService;
                      });
                  final var declaringBeanProvider = supplierContext.beanProvider(declaringClass);
                  taskRegistry.registerWorkflowService(
                      declaringModuleId,
                      declaredProcessId,
                      declaringClass,
                      declaringBeanProvider::getObject,
                      beanResolver,
                      processService);
                }
              }

              return processServiceBean;

            }));

  }

  /**
   * Selects the election cache: an application-provided bean wins over the
   * platform's in-memory default (which is auto-configured conditionally, but may
   * coexist with the application's bean depending on configuration-class ordering).
   *
   * @param candidates All {@code WorkflowAdapterCache} beans
   * @return The cache to use or <code>null</code> if none exists (elections then
   *         probe every time)
   * @throws IllegalStateException If several application-provided beans exist
   */
  private static io.vanillabp.integration.spi.WorkflowAdapterCache selectWorkflowAdapterCache(
      final List<io.vanillabp.integration.spi.WorkflowAdapterCache> candidates) {

    if (candidates.isEmpty()) {
      return null;
    }
    if (candidates.size() == 1) {
      return candidates.getFirst();
    }
    final var applicationProvided = candidates
        .stream()
        .filter(candidate -> candidate
            .getClass() != io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache.class)
        .toList();
    if (applicationProvided.size() == 1) {
      return applicationProvided.getFirst();
    }
    throw new IllegalStateException(
        """
            Several beans implementing io.vanillabp.integration.spi.WorkflowAdapterCache were \
            found (%s)! Define exactly ONE application-provided bean - it replaces VanillaBP's \
            in-memory default."""
            .formatted(
                candidates
                    .stream()
                    .map(candidate -> candidate.getClass().getName())
                    .toList()));

  }

  private static String workflowModuleOf(
      final WorkflowModules allWorkflowModules,
      final Class<?> serviceClass) {

    return allWorkflowModules
        .getWorkflowModules()
        .stream()
        .filter(workflowModule -> workflowModule.isWorkflowServiceKnown(serviceClass))
        .findFirst()
        .map(WorkflowModule::getId)
        .orElseThrow(() -> new IllegalStateException(
            """
                Workflow service class '%s' does not belong to any workflow module! Every \
                @WorkflowService class must be part of a workflow module (a classpath \
                entry having a 'META-INF/workflow-module' marker file) or of the global \
                workflow module (no marker file anywhere in the application)."""
                .formatted(serviceClass.getName())));

  }

}
