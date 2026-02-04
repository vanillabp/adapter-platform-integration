package io.vanillabp.integration.deployment;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

/**
 * Manages deployment of resources using {@link DeploymentService}.
 */
@RequiredArgsConstructor
public class SpringBootDeploymentService {

  private final DeploymentService deploymentService;

  private final WorkflowModules allWorkflowModules;

  /**
   * Triggers loading of all BPMN resources and deployment of them.
   */
  @PostConstruct
  public void deployResources() {

    deploymentService.deployResources(
        getWorkflowModuleIds(),
        this::bpmnResourcesLoader);

  }

  /**
   * Start processing of workflows one the application started.
   */
  @EventListener(ApplicationReadyEvent.class)
  public void startProcessingOfWorkflows() {

    deploymentService.startWorkflowProcessing(
        getWorkflowModuleIds());

  }

  private List<String> getWorkflowModuleIds() {

    return allWorkflowModules
        .getWorkflowModules()
        .stream()
        .map(WorkflowModule::getId)
        .toList();

  }

  /**
   * Recursively reads all BPMN resources from the given location.
   *
   * @param resourceLocation The location to read from
   * @return A map of relative paths to BPMN resources
   */
  public Map<String, InputStream> bpmnResourcesLoader(
      final String resourceLocation) {

    final var resolver = new PathMatchingResourcePatternResolver();

    final var normalizedLocation = resourceLocation.endsWith("/")
        ? resourceLocation
        : resourceLocation
            + "/";

    final var resourcePattern = normalizedLocation
        + "**/*.bpmn";

    final Resource[] resources;
    try {
      resources = resolver.getResources(resourcePattern);
    } catch (final IOException e) {
      throw new IllegalStateException(
          "Failed to resolve BPMN resources from location: "
              + resourceLocation, e
      );
    }

    final var result = new HashMap<String, InputStream>();

    for (final var resource : resources) {
      if (!resource.isReadable()) {
        continue;
      }

      final URI uri;
      try {
        uri = resource.getURI();
      } catch (final IOException e) {
        throw new IllegalStateException("Failed to resolve URI for resource", e);
      }

      final String relativePath = extractRelativePath(uri, normalizedLocation);
      if (relativePath == null) {
        continue;
      }

      try {
        result.put(relativePath, resource.getInputStream());
      } catch (final IOException e) {
        throw new IllegalStateException(
            "Failed to open InputStream for resource: "
                + relativePath, e
        );
      }
    }

    return result;

  }

  private String extractRelativePath(
      final URI uri,
      final String resourceLocation) {

    final var uriString = uri.toString();

    /*
     * Examples:
     * jar:file:/app.jar!/BOOT-INF/classes/bpmn/order/process.bpmn
     * file:/opt/app/bpmn/order/process.bpmn
     * classpath:/bpmn/order/process.bpmn
     */

    final var index = uriString.indexOf(resourceLocation.replace("classpath*:", "")
        .replace("classpath:", "")
        .replace("file:", ""));
    if (index < 0) {
      return null;
    }

    final var relative = uriString.substring(index)
        .replaceFirst("^.*/", "")
        .replace("\\", "/");

    return relative;

  }

}
