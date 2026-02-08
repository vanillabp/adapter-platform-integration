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

      final var adapterLogMessage = "Dummy-Adapter: Starting workflow processing";
      final var extensionLogMessage = "Dummy-Extension: Starting workflow processing";

      final var adapterLogPosition = capturedOutput.indexOf(adapterLogMessage);
      final var extensionLogPosition = capturedOutput.indexOf(extensionLogMessage);

      Assertions.assertTrue(
          adapterLogPosition >= 0,
          "Expected Dummy-Adapter startWorkflowProcessing to be called. Captured output: "
              + capturedOutput);

      Assertions.assertTrue(
          extensionLogPosition >= 0,
          "Expected Dummy-Extension startWorkflowProcessing to be called. Captured output: "
              + capturedOutput);

      Assertions.assertTrue(
          adapterLogPosition < extensionLogPosition,
          "Expected Dummy-Adapter to log before Dummy-Extension. Captured output: "
              + capturedOutput);

    }

  }

}
