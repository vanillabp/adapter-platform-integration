package io.vanillabp.integration.processservice;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.beans.factory.BeanRegistrar;
import org.springframework.beans.factory.BeanRegistry;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.ResolvableType;
import org.springframework.core.env.Environment;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.aggregate.AggregatePersistenceAware;
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

      // find all workflow service classes in classpath
      final var workflowServiceClasses = new ClasspathScanner()
          // find classes annotated with @WorkflowService
          .allClasses(
              "",
              metadataReader -> {
                try {
                  return metadataReader.getAnnotationMetadata().hasAnnotation(WorkflowService.class.getName());
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
                  .orElseThrow();

              final var properties = supplierContext.bean(
                  SpringBootMigrationAdapterAutoConfiguration.BEANNAME_MIGRATIONADAPERPROPERTIES,
                  MigrationAdapterProperties.class);

              // find persistence support for the aggregate class
              final List<AggregatePersistenceAware<?>> aggregatePersistenceAwares = supplierContext
                  .beanProvider(AggregatePersistenceAware.class)
                  .stream()
                  .<AggregatePersistenceAware<?>>map(aware -> (AggregatePersistenceAware<?>) aware)
                  .toList();
              final var aggregatePersistenceAware = (AggregatePersistenceAware<A>) aggregatePersistenceAwares
                  .stream()
                  // calculate distance of classes
                  .map(aware -> Map.entry(
                      aware,
                      AggregatePersistenceResolver.inheritanceDistance(
                          aware.getAggregateClass(),
                          workflowAggregateType
                      )))
                  // filter persistence awares those aggregate type is not assignable to the current aggregate type
                  .filter(awareEntry -> awareEntry.getValue() != Integer.MAX_VALUE)
                  // choose the most specific persistence support in terms of inheritance class distance
                  .min(Comparator.comparingInt(Map.Entry::getValue))
                  .map(Map.Entry::getKey)
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

              return new ProcessServiceSpringBean<A>(
                  workflowModuleId, bpmnProcessId, workflowAggregateType, properties, aggregatePersistenceAware, migratableProcessServices);

            }));

  }

}
