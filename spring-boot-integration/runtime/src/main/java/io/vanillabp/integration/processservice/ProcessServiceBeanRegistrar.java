package io.vanillabp.integration.processservice;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.AwareSelection;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.utils.ClasspathScanner;
import io.vanillabp.integration.utils.SpringDataUtil;
import io.vanillabp.integration.utils.impl.SpringDataUtilBasedAggregatePersistenceSupport;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.spi.process.ProcessService;
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

      // build ProcessService<A> bean definitions
      final Set<Class<?>> processServicesBuilt = new HashSet<>();
      workflowServiceClasses
          .forEach(serviceClass -> {
            final var annotation = serviceClass.getAnnotation(WorkflowService.class);
            final var workflowAggregateType = annotation.workflowAggregateClass();

            // if there is more than one @WorkflowService class for a specific BPMN process ID,
            // then use the one previously built
            if (processServicesBuilt.contains(workflowAggregateType)) {
              return;
            }

            final var bpmnProcessId = Optional.of(annotation
                .bpmnProcess()
                .bpmnProcessId())
                .filter(Predicate.not(String::isEmpty))
                .orElse(serviceClass.getSimpleName());

            registerProcessServiceBean(
                registry,
                workflowServiceClasses,
                serviceClass,
                workflowAggregateType,
                bpmnProcessId);
            processServicesBuilt.add(workflowAggregateType);
          });

    } catch (Exception e) {
      throw new IllegalStateException("Could not register ProcessService beans", e);
    }

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
      final Class<?> serviceClass,
      final Class<A> workflowAggregateType,
      final String bpmnProcessId) {

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

              final var workflowModuleId = allWorkflowModules
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

              return new ProcessServiceSpringBean<A>(
                  workflowModuleId, bpmnProcessId, workflowAggregateType, properties, aggregatePersistenceAware, migratableProcessServices, phaseTwoOutboxResolver, phaseTwoRouter);

            }));

  }

}
