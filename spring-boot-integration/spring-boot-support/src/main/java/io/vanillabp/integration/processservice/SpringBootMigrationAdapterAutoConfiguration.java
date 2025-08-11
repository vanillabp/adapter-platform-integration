package io.vanillabp.integration.processservice;

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
import io.vanillabp.integration.utils.ClasspathScanner;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.spi.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration
@AutoConfigureAfter({
    WorkflowModuleAutoConfiguration.class
})
@EnableConfigurationProperties(SpringBootMigrationAdapterProperties.class)
public class SpringBootMigrationAdapterAutoConfiguration {

  private final Map<Class<?>, MigrationProcessService<?>> connectableServices = new HashMap<>();

  @Bean("VanillaBpMigrationAdapterProperties")
  public static MigrationAdapterProperties migrationAdapterProperties(
      final SpringBootMigrationAdapterProperties properties,
      final WorkflowModules allWorkflowModules,
      final List<AdapterConfigurationBase> adapterConfigurations) {

    final var adaptersLoaded = Optional
        .ofNullable(adapterConfigurations)
        .orElse(List.of())
        .stream()
        .map(AdapterConfigurationBase::getAdapterId)
        .toList();

    final var workflowModulesLoaded = allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getWorkflowModuleId)
        .toList();

    return SpringBootMigrationAdapterTransformer
        .builder()
        .properties(properties)
        .adaptersLoaded(adaptersLoaded)
        .workflowModulesLoaded(workflowModulesLoaded)
        .build()
        .getAndValidatePropertiesConfigured();

  }

  @Bean
  public static BeanDefinitionRegistryPostProcessor buildProcessServices(
      final WorkflowModules allWorkflowModules) {

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

        // associate workflow services to workflow modules
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

              // collect information necessary for bean creation
              final var workflowModuleId = allWorkflowModules
                  .getWorkflowModules()
                  .stream()
                  .filter(workflowModule -> workflowModule.isWorkflowServiceKnown(serviceClass))
                  .findFirst()
                  .map(WorkflowModule::getWorkflowModuleId)
                  .orElseThrow();
              final var bpmProcessId = Optional.of(annotation
                  .bpmnProcess()
                  .bpmnProcessId())
                  .filter(Predicate.not(String::isEmpty))
                  .orElse(serviceClass.getSimpleName());

              // build bean via bean definition
              final var processServiceBeanDefinition = (RootBeanDefinition) BeanDefinitionBuilder
                  .rootBeanDefinition(MigrationProcessService.class)
                  .addConstructorArgValue(workflowModuleId)
                  .addConstructorArgValue(bpmProcessId)
                  .addConstructorArgValue(workflowAggregateType)
                  .addConstructorArgReference("VanillaBpMigrationAdapterProperties")
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
        throw new RuntimeException("Could not register ProcessService beans", e);
      }

    };

  }

}
