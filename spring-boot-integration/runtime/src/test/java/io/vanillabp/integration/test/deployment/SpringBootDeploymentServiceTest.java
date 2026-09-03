package io.vanillabp.integration.test.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;

import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.deployment.SpringBootDeploymentService;
import io.vanillabp.integration.processservice.ProcessServiceSpringBean;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.spi.process.ProcessService;

@ExtendWith(SuppressOutputExtension.class)
public class SpringBootDeploymentServiceTest {

  @Test
  @DisplayName("start() deploys resources, stop() stops workflow processing and process services")
  @SuppressWarnings("unchecked")
  public void lifecycleStartAndStop() {

    final var deploymentService = mock(DeploymentService.class);
    final var workflowModules = new WorkflowModules(List.of(
        WorkflowModule.builder().id("test-module").sourceUri("file:///test").build()));
    final var processService = mock(ProcessServiceSpringBean.class);
    final var processServices = (ObjectProvider<ProcessService<?>>) mock(ObjectProvider.class);
    org.mockito.Mockito.when(processServices.stream()).thenAnswer(invocation -> Stream.of(processService));

    final var testee = new SpringBootDeploymentService(
        deploymentService, workflowModules, processServices);

    assertFalse(testee.isRunning());

    testee.start();

    verify(deploymentService).deployResources(eq(List.of("test-module")), any());
    Assertions.assertTrue(testee.isRunning());

    testee.stop();

    verify(deploymentService).stopWorkflowProcessing(eq(List.of("test-module")));
    verify(processService).stopService();
    assertFalse(testee.isRunning());

  }

  @Test
  @DisplayName("BPMN files in subdirectories keep their relative path")
  public void filesInSubdirectoriesKeepTheirRelativePath() {

    final var testee = new SpringBootDeploymentService(null, null, null);

    final var resources = testee.resourcesLoader("classpath:deployment-test-processes", ".bpmn");

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
  @DisplayName("The extension asked for decides which files of the location are loaded")
  public void theExtensionDecidesWhichFilesAreLoaded() {

    final var testee = new SpringBootDeploymentService(null, null, null);

    // the decision tables of a location are asked for separately, so a DMN file never
    // reaches readBpmn and a BPMN file never reaches readDmn
    assertEquals(
        Set.of("rating.dmn"),
        testee.resourcesLoader("classpath:deployment-test-processes", ".dmn").keySet());

  }

  @Test
  @DisplayName("Resource location with trailing slash is treated the same")
  public void aTrailingSlashIsTreatedTheSame() {

    final var testee = new SpringBootDeploymentService(null, null, null);

    final var resources = testee.resourcesLoader("classpath:deployment-test-processes/", ".bpmn");

    assertEquals(
        Set.of(
            "root-process.bpmn",
            "order/process.bpmn",
            "invoice/process.bpmn"),
        resources.keySet());

  }

}
