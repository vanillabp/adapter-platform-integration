package io.vanillabp.integration.test.workflowversion;

import java.io.IOException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * An extension whose contract asks for a method for the deployed version reports the elements
 * of the model in its <code>wireBpmn</code>, and the start ends where an element has methods but
 * none for the version the BPMS gave the model. The same check the
 * <code>&#64;WorkflowTask</code> methods go through, run by Spring Boot.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionElementsOfTheDeployedVersionTest {

  private static final String APPLICATION_YAML = """
      vanillabp:
        adapters:
          test:
            type: dummy
        workflow-modules:
          test-module:
            adapters:
              test:
                resources-location: classpath*:test-module/processes/workflowversion
            workflows:
              VersionedProcess:
                allow-full-sync-with-bpms: true
      """;

  @Test
  @DisplayName("The start ends where no method of the extension covers the version the BPMS gave the model")
  public void theDeployedVersionNeedsAMethodOfTheExtension() throws IOException {

    ProcessVersionsTest.VersionsConfiguration.AGGREGATES.clear();
    ProcessVersionsTest.VersionsConfiguration.VERSIONS.clear();
    for (var version = 1; version <= 5; ++version) {
      ProcessVersionsTest.VersionsConfiguration.VERSIONS
          .add(DeployedProcessVersion.of(Integer.toString(version), null));
    }

    try {
      // version 4 is noted
      ProcessVersionsTest.VersionsConfiguration.DEPLOYED.set("4");
      try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {
        Assertions.assertNotNull(context);
      }

      // version 5 is not
      ProcessVersionsTest.VersionsConfiguration.DEPLOYED.set("5");
      try (var testApp = buildTestApp()) {
        final var failure = Assertions.assertThrows(
            Exception.class,
            () -> runTestApplication(testApp).close());
        var cause = (Throwable) failure;
        while (cause.getCause() != null) {
          cause = cause.getCause();
        }
        final var message = cause.getMessage();
        Assertions.assertTrue(
            message.startsWith(
                "Version '5' of BPMN process 'VersionedProcess' (workflow module 'test-module') is the version "
                    + "adapter 'test' deployed during this start."),
            message);
        Assertions.assertTrue(
            message.contains(DeployedProcessVersionsCheck.ELEMENTS_SERVED_BY_NO_METHOD_IN_THIS_VERSION),
            message);
        Assertions.assertTrue(
            message.contains("element 'Activity_Versioned' (also matched by 'versionedTask'): @DeployedVersionNote "
                + "method '%s#noteUpToFour' of extension 'deployed-version-note' (version '1-4'), which does not "
                    .formatted(NotedVersionedWorkflowService.class.getName())
                + "cover version '5'."),
            message);
        Assertions.assertTrue(
            message.contains("For @DeployedVersionNote of extension 'deployed-version-note': "
                + DeployedVersionNoteExtension.CONSEQUENCE),
            message);
        Assertions.assertFalse(message.contains("task 'Activity_Versioned'"), "the task is served: "
            + message);
        Assertions.assertFalse(message.contains("the process itself"), "no method notes the process: "
            + message);
      }
    } finally {
      ProcessVersionsTest.VersionsConfiguration.DEPLOYED.set(null);
    }

  }

  private ConfigurableApplicationContext runTestApplication(
      final SpringBootTestApplication testApp) {

    return testApp
        .applicationBuilder(
            DummyAdapterConfiguration.class,
            DummyAdapterProcessServiceConfiguration.class,
            WorkflowModuleAutoConfiguration.class,
            SpringBootMigrationAdapterAutoConfiguration.class,
            TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
            NotedVersionedWorkflowService.class,
            DeployedVersionNoteExtension.class,
            WorkflowModuleConfiguration.class,
            ProcessVersionsTest.VersionsConfiguration.class,
            DeploymentTest.TestConfig.class)
        .run();

  }

  private SpringBootTestApplication buildTestApp() throws IOException {

    return SpringBootTestApplication
        .builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

  }

}
