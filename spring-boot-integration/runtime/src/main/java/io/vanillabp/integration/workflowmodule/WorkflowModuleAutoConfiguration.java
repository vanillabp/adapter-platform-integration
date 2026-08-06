package io.vanillabp.integration.workflowmodule;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.ResourcePatternUtils;

import io.vanillabp.spi.service.WorkflowService;
import lombok.extern.slf4j.Slf4j;

/**
 * Autoconfiguration of VanillaBP workflow modules.
 */
@Slf4j
@AutoConfiguration
public class WorkflowModuleAutoConfiguration {

  /**
   * Build a bean holding all workflow modules found.
   *
   * @param resourceLoader The resource loader used to find META-INF/workflow-module files
   * @return The workflow modules found
   */
  @Bean
  public static WorkflowModules vanillaBpWorkflowModules(
      final ResourceLoader resourceLoader) {

    return determineWorkflowModules(resourceLoader);

  }

  /**
   * Searches for all workflow module descriptors found in classpath to build
   * {@link WorkflowModule} objects. This method is static because it has to be processed
   * by Spring Boot during the very beginning of booting the application.
   * The bean returned is used for loading workflow module-specific config files.
   *
   * @param resourceLoader The resource loader used to find META-INF/workflow-module files
   * @return The workflow modules found
   */
  static WorkflowModules determineWorkflowModules(
      @Nullable final ResourceLoader resourceLoader) {

    try {

      final var workflowModuleDescriptors = ResourcePatternUtils
          .getResourcePatternResolver(resourceLoader)
          .getResources("classpath*:%s".formatted(WorkflowModule.METAINF_WORKFLOWMODULE));
      final var workflowModules = Arrays
          .stream(workflowModuleDescriptors)
          .map(resource -> {
            try {
              final var workflowModuleId = resource
                  .getContentAsString(StandardCharsets.UTF_8)
                  .trim();
              if (workflowModuleId.isEmpty()) {
                throw new IllegalStateException(
                    "Empty workflow module descriptor '"
                        + resource.getURI()
                        + "'");
              }
              // determine the classpath-root prefix of the JAR/directory the descriptor
              // was found in: the descriptor URL minus 'META-INF/workflow-module'
              final var descriptorUrl = resource
                  .getURL()
                  .toString();
              final var sourceUri = descriptorUrl.endsWith(WorkflowModule.METAINF_WORKFLOWMODULE)
                  ? descriptorUrl.substring(0, descriptorUrl.length() - WorkflowModule.METAINF_WORKFLOWMODULE.length())
                  : descriptorUrl;
              return new WorkflowModule(workflowModuleId, sourceUri);
            } catch (IOException e) {
              throw new BeanCreationException(
                  "Could not load workflow module descriptors from classpath '"
                      + WorkflowModule.METAINF_WORKFLOWMODULE
                      + "'", e);
            }
          })
          .toList();

      return new WorkflowModules(workflowModules);

    } catch (IOException e) {
      throw new BeanCreationException(
          "Could not load workflow module descriptors from classpath '"
              + WorkflowModule.METAINF_WORKFLOWMODULE
              + "'", e);
    }

  }

  /**
   * Determines the classpath-root prefix (external URL form) of the JAR or directory
   * the given class was loaded from. This is done based on
   * {@link Class#getResource(String)} instead of the class' protection domain since
   * the URL of the class resource uses the very same protocol as resources resolved
   * by Spring's resource loader. This way matching root prefixes works for all class
   * loaders: plain classpath ({@code file:}), JARs ({@code jar:file:}) and Spring
   * Boot repackaged fat JARs ({@code jar:nested:}).
   *
   * @param clazz The class
   * @return The classpath-root prefix or {@code null} if it cannot be determined
   */
  public static @Nullable String determineClasspathRootPrefix(
      final Class<?> clazz) {

    final var classResourcePath = clazz.getName().replace('.', '/')
        + ".class";
    final var classResourceUrl = clazz.getResource("/"
        + classResourcePath);
    if (classResourceUrl == null) {
      return null;
    }
    final var url = classResourceUrl.toString();
    if (!url.endsWith(classResourcePath)) {
      return null;
    }
    return url.substring(0, url.length() - classResourcePath.length());

  }

  /**
   * Associates workflow services with workflow modules for later usage.
   *
   * @param allWorkflowModules All workflow modules found in the classpath
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
            // register a service class in the workflow module identified by META-INF/workflow-module
            // found in the same JAR/directory: match the classpath-root prefix of the service
            // class against the classpath-root prefix of the workflow module descriptor
            final var serviceClassRootPrefix = determineClasspathRootPrefix(serviceClass);
            final var workflowModuleInServiceClassJar = allWorkflowModules
                .stream()
                .filter(module -> module.getSourceUri().equals(serviceClassRootPrefix))
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
