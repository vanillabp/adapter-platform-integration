package io.vanillabp.integration.test.deployment;

import java.io.IOException;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.adapter.dummy.springboot.deployment.DummyAdapterDeploymentConfiguration;
import io.vanillabp.extension.dummy.springboot.wiring.DummyExtensionWiringConfiguration;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.deployment.DeploymentService;
import io.vanillabp.integration.deployment.SpringBootDeploymentService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.utils.config.JpaSpringDataUtilConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.integration.workflowmodule.WorkflowModules;
import io.vanillabp.intergration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.intergration.extension.spi.ExtensionWiringService;
import io.vanillabp.intergration.test.utils.springboot.SpringBootTestApplication;

@ExtendWith(OutputCaptureExtension.class)
public class DeploymentTest {

  @Configuration
  static class TestConfig {

    @Bean
    public SpringBootDeploymentService springBootDeploymentService(
        final WorkflowModules allWorkflowModules,
        final MigrationAdapterProperties properties,
        final List<AdapterDeploymentService<?, ?, ?>> deploymentServices,
        final List<ExtensionWiringService<?, ?>> wiringServices) {

      final var deploymentService = new DeploymentService(
          properties, deploymentServices, wiringServices);

      return new SpringBootDeploymentService(
          deploymentService, allWorkflowModules);

    }

  }

  @Test
  public void testDeploymentAndStartProcessing(
      final CapturedOutput output) throws IOException {

    try (var testApp = SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml")
        .addResource("test-module/processes/dummy/dummy-process.bpmn")
        .hideResource("META-INF/workflow-module")
        .build(); var context = testApp.applicationBuilder(
            DummyAdapterConfiguration.class,
            DummyAdapterDeploymentConfiguration.class,
            DummyExtensionWiringConfiguration.class,
            WorkflowModuleAutoConfiguration.class,
            SpringBootMigrationAdapterAutoConfiguration.class,
            JpaSpringDataUtilConfiguration.class,
            SampleWorkflowService.class,
            WorkflowModuleConfiguration.class,
            TestConfig.class)
            .run()) {

      final var capturedOutput = output.getAll();

      final var readBpmn = "Dummy-Adapter: Reading BPMN for test-module";
      final var prepareBpmn = "Dummy-Adapter: Preparing BPMN for test-module";
      final var adapterWiring = "Dummy-Adapter: Wiring BPMN for test-module";
      final var extensionWiring = "Dummy-Extension: Wiring BPMN for test-module";
      final var deployResources = "Dummy-Adapter: Deploying resources for test-module";
      final var appStarted = "seconds (process running for";
      final var adapterStartProcessing = "Dummy-Adapter: Starting workflow processing for test-module";
      final var extensionStartProcessing = "Dummy-Extension: Starting workflow processing for test-module";

      final var readBpmnPos = capturedOutput.indexOf(readBpmn);
      final var prepareBpmnPos = capturedOutput.indexOf(prepareBpmn);
      final var adapterWiringPos = capturedOutput.indexOf(adapterWiring);
      final var extensionWiringPos = capturedOutput.indexOf(extensionWiring);
      final var deployResourcesPos = capturedOutput.indexOf(deployResources);
      final var appStartedPos = capturedOutput.indexOf(appStarted);
      final var adapterStartProcessingPos = capturedOutput.indexOf(adapterStartProcessing);
      final var extensionStartProcessingPos = capturedOutput.indexOf(extensionStartProcessing);

      Assertions.assertTrue(readBpmnPos >= 0,
          "Expected '"
              + readBpmn
              + "'. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(prepareBpmnPos >= 0,
          "Expected '"
              + prepareBpmn
              + "'. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(adapterWiringPos >= 0,
          "Expected '"
              + adapterWiring
              + "'. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(extensionWiringPos >= 0,
          "Expected '"
              + extensionWiring
              + "'. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(deployResourcesPos >= 0,
          "Expected '"
              + deployResources
              + "'. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(appStartedPos >= 0,
          "Expected '"
              + appStarted
              + "'. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(adapterStartProcessingPos >= 0,
          "Expected '"
              + adapterStartProcessing
              + "'. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(extensionStartProcessingPos >= 0,
          "Expected '"
              + extensionStartProcessing
              + "'. Captured output: "
              + capturedOutput);

      Assertions.assertTrue(readBpmnPos < prepareBpmnPos,
          "Expected reading BPMN before preparing. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(prepareBpmnPos < adapterWiringPos,
          "Expected preparing BPMN before adapter wiring. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(adapterWiringPos < extensionWiringPos,
          "Expected adapter wiring before extension wiring. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(extensionWiringPos < deployResourcesPos,
          "Expected extension wiring before deploying resources. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(deployResourcesPos < appStartedPos,
          "Expected deploying resources before app started. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(appStartedPos < adapterStartProcessingPos,
          "Expected app started before adapter starts processing. Captured output: "
              + capturedOutput);
      Assertions.assertTrue(adapterStartProcessingPos < extensionStartProcessingPos,
          "Expected adapter starts processing before extension. Captured output: "
              + capturedOutput);

    }

  }

}
