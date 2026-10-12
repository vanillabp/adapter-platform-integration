package io.vanillabp.integration.test.deployment;

import java.io.IOException;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Some adapters cannot ask their BPMS whether it holds a workflow. Such an adapter answers
 * the election with yes. That is right while it is the only BPMS configured. With a second
 * one it is a guess. The election stops at the first adapter which says yes. So a guessing
 * adapter takes the operations of every adapter behind it in the list.
 * <p>
 * VanillaBP refuses to boot that combination. An application which wants it anyway has to
 * say so in its configuration. This test checks both cases. The dummy adapter with the id
 * 'test' plays the adapter which has to guess (like a Camunda 8 cluster without secondary
 * storage, or the Process-Engine-API).
 */
@ExtendWith(SuppressOutputExtension.class)
public class GuessingAdapterStartupTest {

  /**
   * Lets the adapter id 'test' report that it cannot locate workflows, and 'test2' that
   * it can.
   */
  @Configuration
  static class GuessingAdapterConfiguration {

    @Bean
    DummyTaskAwarenessSource guessingAdapter() {

      return new DummyTaskAwarenessSource() {

        @Override
        public WorkflowAwareness awarenessOfTask(
            final String adapterId,
            final Object workflowAggregateId,
            final String taskId) {

          return WorkflowAwareness.UNKNOWN_TO_BPMS;

        }

        @Override
        public boolean canLocateWorkflows(
            final String adapterId) {

          return !"test".equals(adapterId);

        }

      };

    }

  }

  private static final String APPLICATION_YAML = """
      vanillabp:
        prioritized-adapters:
          - test
          - test2
        adapters:
          test:
            type: dummy
          test2:
            type: dummy
        workflow-modules:
          test-module:
            adapters:
              test:
                resources-location: classpath*:test-module/processes/dummy
              test2:
                resources-location: classpath*:test-module/processes/dummy
      """;

  private static final String ACCEPTING_APPLICATION_YAML = APPLICATION_YAML + """
            election:
              guessing-adapters: ACCEPTED
      """;

  private SpringBootTestApplication testApplication(
      final String applicationYaml) throws IOException {

    return SpringBootTestApplication
        .builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", applicationYaml)
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

  }

  private org.springframework.boot.builder.SpringApplicationBuilder applicationOf(
      final SpringBootTestApplication testApp) {

    return testApp
        .applicationBuilder(
            DummyAdapterConfiguration.class,
            DummyAdapterProcessServiceConfiguration.class,
            WorkflowModuleAutoConfiguration.class,
            SpringBootMigrationAdapterAutoConfiguration.class,
            TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
            TestTransactionRunnerConfiguration.class,
            SampleWorkflowService.class,
            WorkflowModuleConfiguration.class,
            GuessingAdapterConfiguration.class,
            // brings the deployment service. The check runs after the adapters deployed,
            // and the start ends right after it
            DeploymentTest.TestConfig.class)
        .properties(io.vanillabp.integration.test.ThePlaceholderModel.BELONGS_TO_SOMEBODY_ELSE);

  }

  @Test
  @DisplayName("A guessing adapter next to another one ends the boot, naming it and the way out")
  public void aGuessingAdapterNextToAnotherEndsTheBoot() throws IOException {

    try (var testApp = testApplication(APPLICATION_YAML)) {

      // the refusal is thrown at the end of the start, inside the lifecycle. Spring wraps
      // what a lifecycle bean throws while the context is refreshed
      final var failure = Assertions.assertThrows(
          org.springframework.context.ApplicationContextException.class,
          () -> applicationOf(testApp)
              .run()
              .close());

      var refusal = (Throwable) failure;
      while (refusal.getCause() != null) {
        refusal = refusal.getCause();
      }
      Assertions.assertInstanceOf(IllegalStateException.class, refusal, String.valueOf(refusal));
      final var message = refusal.getMessage();
      Assertions.assertTrue(message.contains("test"), message);
      Assertions.assertTrue(message.contains("test-module"), message);
      Assertions.assertTrue(message.contains("SampleWorkflowService"), message);
      // the ways out: give the BPMS its query API, prioritize one adapter, or accept it
      Assertions.assertTrue(message.contains("secondary storage"), message);
      Assertions.assertTrue(
          message.contains("vanillabp.workflow-modules.test-module.election.guessing-adapters"),
          message);

    }

  }

  @Test
  @DisplayName("The application may accept the routing by list order, and keeps the warning")
  public void anAcceptedGuessingAdapterBootsWithAWarning(
      final CapturedOutput output) throws IOException {

    try (var testApp = testApplication(ACCEPTING_APPLICATION_YAML); var context = applicationOf(testApp).run()) {

      Assertions.assertNotNull(context);
      Assertions.assertTrue(
          output.getAll().contains("cannot ask their BPMS"),
          "the decision has to stay visible in the log but got: "
              + output.getAll());

    }

  }

  @Test
  @DisplayName("A single prioritized adapter which has to guess is nobody's problem")
  public void aSingleGuessingAdapterBoots() throws IOException {

    final var oneAdapter = """
        vanillabp:
          prioritized-adapters:
            - test
          adapters:
            test:
              type: dummy
          workflow-modules:
            test-module:
              adapters:
                test:
                  resources-location: classpath*:test-module/processes/dummy
        """;

    try (var testApp = testApplication(oneAdapter); var context = applicationOf(testApp).run()) {

      // there is nothing to choose from. Whatever this adapter answers, it is the
      // only BPMS which could hold the workflow
      Assertions.assertNotNull(context);

    }

  }

}
