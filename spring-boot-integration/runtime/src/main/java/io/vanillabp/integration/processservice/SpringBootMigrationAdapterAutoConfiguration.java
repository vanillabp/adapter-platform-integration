package io.vanillabp.integration.processservice;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;

import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processervice.MigrationProcessService;
import io.vanillabp.integration.config.SpringBootMigrationAdapterProperties;
import io.vanillabp.integration.config.SpringBootMigrationAdapterTransformer;
import io.vanillabp.integration.spi.aggregate.AggregatePersistenceAware;
import io.vanillabp.integration.utils.ClasspathScanner;
import io.vanillabp.integration.utils.SpringDataUtil;
import io.vanillabp.integration.utils.impl.SpringDataUtilBasedAggregatePersistenceSupport;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.intergration.adapter.spi.MigratableProcessService;
import io.vanillabp.spi.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;

/**
 * Autoconfiguration of VanillaBP adapters.
 */
@Slf4j
@Configuration
@AutoConfigureAfter({
    WorkflowModuleAutoConfiguration.class
})
@EnableConfigurationProperties(SpringBootMigrationAdapterProperties.class)
public class SpringBootMigrationAdapterAutoConfiguration {

  static final String BEANNAME_MIGRATIONADAPERPROPERTIES = "VanillaBpMigrationAdapterProperties";

  private final Map<Class<?>, MigrationProcessService<?>> connectableServices = new HashMap<>();

  /**
   * Maps and validates VanillaBP properties (specific to Spring Boot) to
   * {@link MigrationAdapterProperties} bean. It is used by common adapter
   * implementation of module "migration-adapter".
   *
   * @param properties The Spring Boot specific properties
   * @param allWorkflowModules All workflow modules found in classpath
   * @param adapterConfigurations Configuration beans of adapters found in classpath
   * @return The properties bean not specific to Spring Boot
   */
  @Bean(BEANNAME_MIGRATIONADAPERPROPERTIES)
  public static MigrationAdapterProperties migrationAdapterProperties(
      final SpringBootMigrationAdapterProperties properties,
      final WorkflowModules allWorkflowModules,
      final List<AdapterConfigurationBase> adapterConfigurations) {

    final var adaptersLoaded = Optional
        .ofNullable(adapterConfigurations)
        .orElse(List.of())
        .stream()
        .map(AdapterConfigurationBase::getAdapterType)
        .toList();

    final var workflowModuleIds = allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        .toList();

    return SpringBootMigrationAdapterTransformer
        .builder()
        .properties(properties)
        .adaptersFound(adaptersLoaded)
        .workflowModulesFound(workflowModuleIds)
        .build()
        .getAndValidatePropertiesConfigured();

  }

  /**
   * Builds {@link io.vanillabp.spi.process.ProcessService} beans for each
   * aggregate type of workflow services found in classpath.
   *
   * @param allWorkflowModules All workflow modules found in classpath
   * @return A {@link BeanDefinitionRegistryPostProcessor} adding all {@link io.vanillabp.spi.process.ProcessService} beans necessary
   */
  @Bean
  public static BeanDefinitionRegistryPostProcessor buildProcessServices(
      final WorkflowModules allWorkflowModules,
      final Optional<SpringDataUtil> springDataUtil,
      final List<AggregatePersistenceAware<?>> aggregatePersistenceAwares,
      final List<MigratableProcessService<?>> migratableProcessServices) {

    return registry -> {

      try {

        // find all workflow service classes in classpath
        final var workflowServiceClasses = ClasspathScanner
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

        // associate workflow services with workflow modules
        WorkflowModuleAutoConfiguration.registerProcessServices(
            allWorkflowModules.getWorkflowModules(),
            workflowServiceClasses);

        // build ProcessService<A> beans
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

              if (springDataUtil.isEmpty()) {
                throw new IllegalStateException(
                    """
                        Spring Data Util bean not found! To solve this either
                        - add spring-boot-starter-data-jpa to classpath and configure a data source, if you use JPA for persistence of aggregates
                        - add @Import(io.vanillabp.integration.utils.impl.MongoDbSpringDataUtilConfiguration) to your main application class, if you use MongoDb for persistence of aggregates
                        - add your own implementation of io.vanillabp.integration.utils.SpringDataUtil, if you use an alternative persistence""");
              }

              // find persistence support class for the aggregate class
              @SuppressWarnings({
                  "rawtypes", "unchecked"
              })
              final var aggregatePersistenceAware = aggregatePersistenceAwares
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
                  // if none found, fall back to persistence support based on Spring Data Util bean
                  .map(Map.Entry::getKey)
                  .orElse(new SpringDataUtilBasedAggregatePersistenceSupport(
                      springDataUtil.get(), workflowAggregateType));

              // collect information necessary for bean creation
              final var workflowModuleId = allWorkflowModules
                  .getWorkflowModules()
                  .stream()
                  .filter(workflowModule -> workflowModule.isWorkflowServiceKnown(serviceClass))
                  .findFirst()
                  .map(WorkflowModule::getId)
                  .orElseThrow();
              final var bpmProcessId = Optional.of(annotation
                  .bpmnProcess()
                  .bpmnProcessId())
                  .filter(Predicate.not(String::isEmpty))
                  .orElse(serviceClass.getSimpleName());

              // build bean via bean definition: This means to build it as late as possible.
              // A bean definition is a promise to Spring that this bean will be available and
              // can be used for wiring before the bean is created.
              // The reason for this is to avoid circular dependencies between the class
              // annotated by @WorkflowService and the ProcessService bean.
              final var processServiceBeanDefinition = (RootBeanDefinition) BeanDefinitionBuilder
                  .rootBeanDefinition(ProcessServiceSpringBean.class)
                  .addConstructorArgValue(workflowModuleId)
                  .addConstructorArgValue(bpmProcessId)
                  .addConstructorArgValue(workflowAggregateType)
                  .addConstructorArgReference(BEANNAME_MIGRATIONADAPERPROPERTIES)
                  .addConstructorArgValue(aggregatePersistenceAware)
                  .addConstructorArgValue(migratableProcessServices)
                  .getBeanDefinition();
              processServiceBeanDefinition.setTargetType(
                  ResolvableType.forClassWithGenerics(io.vanillabp.spi.process.ProcessService.class,
                      workflowAggregateType));
              registry.registerBeanDefinition(
                  "VanillaBP_ProcessService_%s".formatted(workflowAggregateType.getName()),
                  processServiceBeanDefinition
              );
              processServicesBuilt.add(workflowAggregateType);
            });
      } catch (Exception e) {
        throw new IllegalStateException("Could not register ProcessService beans", e);
      }

    };

  }

}
