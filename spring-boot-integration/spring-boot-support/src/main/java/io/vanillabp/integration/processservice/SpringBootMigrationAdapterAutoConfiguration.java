package io.vanillabp.integration.processservice;


import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;

import io.vanillabp.integration.adapter.migration.processervice.MigrationProcessService;
import io.vanillabp.integration.adapters.AdapterConfigurationBase;
import io.vanillabp.integration.config.MigrationAdapterProperties;
import io.vanillabp.integration.config.SpringBootMigrationAdapterProperties;
import io.vanillabp.integration.config.SpringBootMigrationAdapterTransformer;
import io.vanillabp.integration.modules.WorkflowModuleProperties;
import io.vanillabp.integration.utils.ClasspathScanner;
import io.vanillabp.spi.service.WorkflowService;

@Configuration
@EnableConfigurationProperties(SpringBootMigrationAdapterProperties.class)
public class SpringBootMigrationAdapterAutoConfiguration {

  private final Map<Class<?>, MigrationProcessService<?>> connectableServices = new HashMap<>();

  @Bean("VanillaBpMigrationAdapterProperties")
  public static MigrationAdapterProperties migrationAdapterProperties(
      final SpringBootMigrationAdapterProperties properties,
      final List<WorkflowModuleProperties> workflowModules,
      final List<AdapterConfigurationBase> adapterConfigurations) {

    final var adaptersLoaded = Optional
        .ofNullable(adapterConfigurations)
        .orElse(List.of())
        .stream()
        .map(AdapterConfigurationBase::getAdapterId)
        .toList();

    final var workflowModulesLoaded = Optional
        .ofNullable(workflowModules)
        .orElse(List.of())
        .stream()
        .map(WorkflowModuleProperties::getWorkflowModuleId)
        .toList();

    return SpringBootMigrationAdapterTransformer
        .builder()
        .properties(properties)
        .adaptersLoaded(adaptersLoaded)
        .workflowModulesLoaded(workflowModulesLoaded)
        .build()
        .getAndValidatePropertiesConfigured();

  }

  /*
  @Bean
  @Primary
  @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
  public <DE> ProcessService<?> migrationProcessService(
      final InjectionPoint injectionPoint,
      final MigrationAdapterProperties properties) {
  
    final ParameterizedType processServiceGenericType;
    if (injectionPoint.getMethodParameter() != null) {
      processServiceGenericType = (ParameterizedType) injectionPoint
          .getMethodParameter()
          .getGenericParameterType();
    } else if (injectionPoint.getField() != null) {
      processServiceGenericType = (ParameterizedType) injectionPoint
          .getField()
          .getGenericType();
    } else {
      throw new RuntimeException(
          "Unsupported injection of ProcessService, only field-, constructor- and method-parameter-injection allowed!");
    }
    @SuppressWarnings("unchecked")
    final Class<DE> workflowAggregateClass = (Class<DE>) processServiceGenericType.getActualTypeArguments()[0];
  
    final var existingService = connectableServices.get(workflowAggregateClass);
    if (existingService != null) {
      return existingService;
    }
  
    final var result = new MigrationProcessService<>(
        workflowAggregateClass, properties.getAdapters(),
        // TODO pass workflowModuleId and bpmnProcessId
        properties.getPrioritizedAdaptersFor("test", "test"));
  
    connectableServices.put(workflowAggregateClass, result);
  
    return result;
  
  }
  */

  @Bean
  public static BeanDefinitionRegistryPostProcessor buildProcessServices() {

    return registry -> {

      try {
        ClasspathScanner
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
            )
            .stream()
            // determine aggregate class
            .map(clasz -> clasz.getAnnotation(WorkflowService.class))
            .map(WorkflowService::workflowAggregateClass)
            // build ProcessService<A> beans
            .forEach(workflowAggregateClass -> {
              // TODO pass workflowModuleId and bpmnProcessId
              final var processServiceBeanDefinition = (RootBeanDefinition) BeanDefinitionBuilder
                  .rootBeanDefinition(MigrationProcessService.class)
                  .addConstructorArgValue(workflowAggregateClass)
                  .addConstructorArgReference("VanillaBpMigrationAdapterProperties")
                  .getBeanDefinition();
              processServiceBeanDefinition.setTargetType(
                  ResolvableType.forClassWithGenerics(io.vanillabp.spi.process.ProcessService.class,
                      workflowAggregateClass));
              registry.registerBeanDefinition(
                  "VanillaBP_ProcessService_"
                      + workflowAggregateClass.getName(),
                  processServiceBeanDefinition
              );
            });
      } catch (Exception e) {
        throw new RuntimeException("Could not register ProcessService beans", e);
      }

    };

  }

}
