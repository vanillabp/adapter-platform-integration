package io.vanillabp.integration.test.deployment;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.adapter.dummy.springboot.deployment.DummyAdapterDeploymentConfiguration;
import io.vanillabp.adapter.dummy.springboot.processservice.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Documents the current, honest behavior for TWO adapter ids of ONE type (B2
 * regression test at the platform level): the dummy adapter builds a single process
 * service serving the first configured id only, so the election's fail-fast fires at
 * startup with a guiding message naming the unserved adapter id - workflows must
 * never silently start in the wrong BPMS. Full per-adapter-id multiplicity is
 * introduced by the adapter-config-model story (26d), which will turn this failure
 * into a green boot.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TwoAdapterIdsOfOneTypeTest {

  private static final String APPLICATION_YAML = """
      vanillabp:
        prioritized-adapters:
          - test
          - test2
        adapters:
          test:
            type: dummy
            test: 1
          test2:
            type: dummy
        workflow-modules:
          test-module:
            adapters:
              test:
                resources-location: classpath*:test-module/processes/dummy
              test2:
                resources-location: classpath*:test-module/processes/dummy
      test-module:
        nothing: there
      """;

  @Test
  public void secondIdWithoutProcessServiceFailsFastWithGuidingMessage() throws IOException {

    try (var testApp = SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/dummy/dummy-process.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build()) {

      final var exception = Assertions.assertThrows(
          Exception.class,
          () -> testApp.applicationBuilder(
              DummyAdapterConfiguration.class,
              DummyAdapterDeploymentConfiguration.class,
              DummyAdapterProcessServiceConfiguration.class,
              WorkflowModuleAutoConfiguration.class,
              SpringBootMigrationAdapterAutoConfiguration.class,
              TestPersistenceConfiguration.class,
              SampleWorkflowService.class,
              WorkflowModuleConfiguration.class)
              .run()
              .close());

      // find the election's guiding message in the failure chain (rendered as a
      // stack trace since Spring nests the causes over several exception types)
      final var stringWriter = new StringWriter();
      exception.printStackTrace(new PrintWriter(stringWriter));
      final var failure = stringWriter.toString();

      // the dummy adapter's single process service serves ONE of the two configured
      // ids (which one depends on the adapter-map iteration order) - the OTHER id
      // is unserved and has to be named by the guiding message
      Assertions.assertTrue(
          failure.contains("No VanillaBP adapter serves the prioritized adapter id 'test"),
          "expected the unserved adapter id in the guiding message but got: "
              + failure);
      Assertions.assertTrue(failure.contains("test-module"));
      Assertions.assertTrue(failure.contains("SampleWorkflowService"));
      Assertions.assertTrue(failure.contains("classpath"));
      Assertions.assertTrue(failure.contains("vanillabp.prioritized-adapters"));

    }

  }

}
