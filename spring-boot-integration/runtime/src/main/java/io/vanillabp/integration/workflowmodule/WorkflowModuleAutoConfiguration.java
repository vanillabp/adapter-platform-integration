package io.vanillabp.integration.workflowmodule;


import java.io.IOException;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;

import io.vanillabp.spi.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;

/**
 * Autoconfiguration of VanillaBP workflow modules.
 */
@Slf4j
@Configuration
public class WorkflowModuleAutoConfiguration {

  /**
   * Searches for all workflow module descriptors found in classpath to build
   * {@link WorkflowModule} objects. This method is static because it has to be processed
   * by Spring Boot during the very beginning of booting the application.
   * The bean returned is used for loading workflow module-specific config files.
   *
   * @param resourceLoader The resource loader used to find META-INF/workflow-module files
   * @return The workflow modules found
   */
  @Bean
  public static WorkflowModules vanillaBpWorkflowModules(
      final ResourceLoader resourceLoader) {

    try {

      final var workflowModuleDescriptors = ResourcePatternUtils
          .getResourcePatternResolver(resourceLoader)
          .getResources("classpath*:%s".formatted(WorkflowModule.METAINF_WORKFLOWMODULE));
      final var workflowModules = Arrays
          .stream(workflowModuleDescriptors)
          .map(resource -> {
            try {
              final var workflowModuleId = resource
                  .getContentAsString(Charset.defaultCharset())
                  .trim();
              if (workflowModuleId.isEmpty()) {
                throw new IllegalStateException(
                    "Empty workflow module descriptor '"
                        + resource.getURI()
                        + "'");
              }
              return new WorkflowModule(workflowModuleId, resource.getURI());
            } catch (IOException e) {
              throw new BeanCreationException(
                  "Could not load workflow module descriptors from classpath '"
                      + WorkflowModule.METAINF_WORKFLOWMODULE
                      + "'");
            }
          })
          .toList();

      return new WorkflowModules(workflowModules);

    } catch (IOException e) {
      throw new BeanCreationException(
          "Could not load workflow module descriptors from classpath '"
              + WorkflowModule.METAINF_WORKFLOWMODULE
              + "'");
    }

  }

  /**
   * Associates workflow services to workflow modules for later usage.
   *
   * @param allWorkflowModules All workflow modules found in classpath
   * @param allWorkflowServiceClasses All classes of workflow services found
   */
  public static void registerProcessServices(
      final List<WorkflowModule> allWorkflowModules,
      final List<Class<?>> allWorkflowServiceClasses) {

    final var globalWorkflowModuleWorkflowServiceClasses = new LinkedList<Class<?>>();
    final var globalClasspathWorkflowModuleDescriptors = new LinkedList<>(allWorkflowModules);

    // apply all service classes to their workflow modules
    allWorkflowServiceClasses
        .forEach(serviceClass -> {

          try {
            // register service class in workflow module identified by META-INF/workflow-service
            // found in the same JAR/directory
            final var serviceClassSourceUri = serviceClass
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI();
            final URI serviceClassWorkflowDescriptorUrl;
            if (serviceClassSourceUri.getPath().endsWith(".jar")) {
              serviceClassWorkflowDescriptorUrl = URI
                  .create(
                      "jar:%s!/%s".formatted(serviceClassSourceUri.toString(), WorkflowModule.METAINF_WORKFLOWMODULE));
            } else {
              serviceClassWorkflowDescriptorUrl = serviceClassSourceUri
                  .resolve(WorkflowModule.METAINF_WORKFLOWMODULE);
            }
            final var workflowModuleInServiceClassJar = allWorkflowModules
                .stream()
                .filter(module -> module.getSourceUri().equals(serviceClassWorkflowDescriptorUrl))
                .findFirst();
            if (workflowModuleInServiceClassJar.isPresent()) {
              globalClasspathWorkflowModuleDescriptors.remove(workflowModuleInServiceClassJar.get());
              workflowModuleInServiceClassJar.get().addWorkflowService(serviceClass);
              return;
            }

            // load workflow module ID from META-INF/workflow-module of the Java module JAR
            // the workflow service class belongs to
            // TODO: NOT YET SUPPORTED

            // collect service class for later registration in global workflow module
            globalWorkflowModuleWorkflowServiceClasses.add(serviceClass);
          } catch (Exception e) {
            throw new IllegalStateException(
                "Could not to determine workflow module id", e);
          }

        });

    if (globalClasspathWorkflowModuleDescriptors.size() > 1) {
      throw new IllegalStateException("""
          Multiple workflow module descriptor files %s were found in modules which do not contain any service class annotated with @%s:
            - %s
          """
          .formatted(
              WorkflowModule.METAINF_WORKFLOWMODULE,
              WorkflowService.class.getName(),
              globalClasspathWorkflowModuleDescriptors
                  .stream()
                  .map(WorkflowModule::getSourceUri)
                  .map(URI::toString)
                  .collect(Collectors.joining("\n  - "))));

    }

    // no global workflow module descriptor file
    if (globalClasspathWorkflowModuleDescriptors.isEmpty()) {
      // if no workflow services left, then it is OK
      if (globalWorkflowModuleWorkflowServiceClasses.isEmpty()) {
        return;
      }

      throw new IllegalStateException("""
          There is no workflow module descriptor file %s in the application's module nor, if in a separate module, in the modules of these workflow service classes:
            - %s
          """
          .formatted(
              WorkflowModule.METAINF_WORKFLOWMODULE,
              globalWorkflowModuleWorkflowServiceClasses
                  .stream()
                  .map(Class::getName)
                  .collect(Collectors.joining("\n  - "))
          ));
    }

    // associate workflow services to global workflow module if not yet associated to another workflow module
    final var globalClasspathWorkflowModuleDescriptor = globalClasspathWorkflowModuleDescriptors
        .getFirst();
    globalClasspathWorkflowModuleDescriptor.addWorkflowServices(globalWorkflowModuleWorkflowServiceClasses);

  }

}
