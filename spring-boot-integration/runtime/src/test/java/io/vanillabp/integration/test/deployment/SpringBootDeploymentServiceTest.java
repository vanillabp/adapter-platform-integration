package io.vanillabp.integration.test.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.vanillabp.integration.deployment.SpringBootDeploymentService;

public class SpringBootDeploymentServiceTest {

  @Test
  @DisplayName("BPMN files in subdirectories keep their relative path")
  public void bpmnResourcesLoaderKeepsSubdirectories() {

    final var testee = new SpringBootDeploymentService(null, null);

    final var resources = testee.bpmnResourcesLoader("classpath:deployment-test-processes");

    // same-named BPMN files in different subdirectories must not overwrite each other
    assertEquals(
        Set.of(
            "root-process.bpmn",
            "order/process.bpmn",
            "invoice/process.bpmn"),
        resources.keySet());
    resources
        .values()
        .forEach(inputStream -> assertNotNull(inputStream));

  }

  @Test
  @DisplayName("Resource location with trailing slash is treated the same")
  public void bpmnResourcesLoaderWithTrailingSlash() {

    final var testee = new SpringBootDeploymentService(null, null);

    final var resources = testee.bpmnResourcesLoader("classpath:deployment-test-processes/");

    assertEquals(
        Set.of(
            "root-process.bpmn",
            "order/process.bpmn",
            "invoice/process.bpmn"),
        resources.keySet());

  }

}
