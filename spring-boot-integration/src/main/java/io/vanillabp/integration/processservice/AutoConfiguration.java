package io.vanillabp.integration.processservice;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;

import io.vanillabp.integration.adapter.migration.processervice.MigrationProcessService;
import io.vanillabp.integration.config.VanillaBpProperties;
import io.vanillabp.integration.utils.ClasspathScanner;
import io.vanillabp.spi.service.WorkflowService;

@Configuration
//@EnableConfigurationProperties(VanillaBpProperties.class)
public class AutoConfiguration {

  @Autowired
  private Optional<VanillaBpProperties> properties;

  @Bean
  public BeanDefinitionRegistryPostProcessor buildProcessServices() {

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
              final var processServiceBeanDefinition = new RootBeanDefinition(
                  MigrationProcessService.class, () -> new MigrationProcessService(workflowAggregateClass));
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
