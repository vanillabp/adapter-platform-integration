package io.vanillabp.integration.test.workflowstart;

import java.io.IOException;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.bpmsdouble.DummyBpmsInitiatedStartSource;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Acceptance test of the rule that <code>ProcessService#startWorkflowByMessage</code> starts
 * the process of its own process service and no other. The dummy adapter stands in for an
 * adapter reading its model: 'TimerProcess' starts on the message 'OrderPlaced', and a
 * message the model does not know is refused before anything is saved or planned. An adapter
 * which reports no messages is not checked, and the start says so.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AMessageStartsOnlyItsOwnProcessTest {

  private static final String PROCESS = "TimerProcess";

  private static final String START_MESSAGE = "OrderPlaced";

  /**
   * The sentence the start writes for a process whose adapter reported no messages. Both
   * cases below quote it: the one which has to say it and the one which must not.
   */
  private static final String NOT_CHECKED = "so ProcessService#startWorkflowByMessage is not checked for it";

  /**
   * The model of 'TimerProcess' as the dummy adapter reports it: the two start events the
   * workflow service of this scenario serves, plus the message which starts the process.
   */
  @Configuration
  static class ReportedStartMessagesConfiguration {

    @Bean
    DummyBpmsInitiatedStartSource startEventsWithMessages() {

      return new StartEventsOfTimerProcess() {

        @Override
        public Collection<String> startMessagesOf(
            final String adapterId,
            final String workflowModuleId,
            final String bpmnProcessId) {

          return PROCESS.equals(bpmnProcessId)
              ? List.of(START_MESSAGE)
              : List.of();

        }

      };

    }

  }

  /**
   * The same model reported by an adapter which says nothing about messages: an adapter
   * which cannot read its model, or one which does not know about the report yet.
   */
  @Configuration
  static class NoReportedStartMessagesConfiguration {

    @Bean
    DummyBpmsInitiatedStartSource startEventsWithoutMessages() {

      return new StartEventsOfTimerProcess();

    }

  }

  /**
   * The start events the workflow service of this scenario serves. Without them it does not
   * boot, because it has a method for each.
   */
  static class StartEventsOfTimerProcess implements DummyBpmsInitiatedStartSource {

    @Override
    public Collection<BpmsInitiatedStartSpec> startEventsOf(
        final String adapterId,
        final String workflowModuleId,
        final String bpmnProcessId) {

      return PROCESS.equals(bpmnProcessId)
          ? List
              .of(
                  BpmsInitiatedStartSpec.of("DailyTimer", BpmsStartTrigger.Kind.TIMER),
                  new BpmsInitiatedStartSpec("SignalStart", BpmsStartTrigger.Kind.SIGNAL, "OrderReceived"),
                  BpmsInitiatedStartSpec.of("ReportingStart", BpmsStartTrigger.Kind.CONDITIONAL))
          : List.of();

    }

  }

  /**
   * Makes the dummy adapter wire 'TimerProcess' like a deployed process. A process nobody
   * wired counts as one the application only declares, and the start says nothing about
   * such a process.
   */
  @Configuration
  static class TimerProcessIsDeployedConfiguration {

    @Bean
    DummyTaskWiringSource timerProcessHasNoTasks() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> List.<BpmnTaskSpec>of();

    }

  }

  private static final String APPLICATION_YAML = """
      vanillabp:
        adapters:
          test:
            type: dummy
            test: 1
        workflow-modules:
          test-module:
            adapters:
              test:
                resources-location: classpath*:test-module/processes/workflowstart
            workflows:
              TimerProcess:
                allow-full-sync-with-bpms: true
                declared-aggregate-values: [ "*" ]
      """;

  @BeforeEach
  public void startFromNothing() {

    BpmsInitiatedStartTest.WorkflowStartConfiguration.AGGREGATES.clear();
    TestPhaseTwoOutboxConfiguration.clear();

  }

  @Test
  @DisplayName("A message which starts the process of the process service is accepted")
  public void aMessageOfTheOwnProcessIsAccepted(
      final CapturedOutput output) throws IOException {

    try (var testApp = buildTestApp(); var context = runTestApplication(
        testApp, ReportedStartMessagesConfiguration.class)) {

      startByMessage(context, "accepted", START_MESSAGE);

      Assertions.assertNotNull(
          BpmsInitiatedStartTest.WorkflowStartConfiguration.AGGREGATES.get("accepted"),
          "the aggregate was not saved");
      final var planned = plannedStartsByMessage();
      Assertions.assertEquals(1, planned.size(), "expected one planned start: "
          + planned);
      Assertions.assertEquals("accepted", planned.getFirst().workflowAggregateId());
      Assertions.assertEquals(PROCESS, planned.getFirst().bpmnProcessId());
      Assertions.assertEquals(START_MESSAGE, planned.getFirst().args().get(PhaseTwoCall.ARG_MESSAGE_NAME));

      Assertions.assertFalse(
          output.getAll().contains(NOT_CHECKED),
          "the adapter reported its messages, so the start has nothing to say about them");

    }

  }

  @Test
  @DisplayName("A message which does not start the process of the process service is refused before phase one")
  public void aMessageOfAnotherProcessIsRefused() throws IOException {

    try (var testApp = buildTestApp(); var context = runTestApplication(
        testApp, ReportedStartMessagesConfiguration.class)) {

      final var refusal = Assertions.assertThrows(
          IllegalArgumentException.class,
          () -> startByMessage(context, "refused", "InvoicePaid"));

      Assertions.assertTrue(
          refusal
              .getMessage()
              .contains("Message 'InvoicePaid' does not start BPMN process 'TimerProcess' of workflow module "
                  + "'test-module'"),
          refusal.getMessage());
      Assertions.assertTrue(
          refusal
              .getMessage()
              .contains("The messages which start this process in the model adapter 'test' deployed are: "
                  + "'OrderPlaced'."),
          refusal.getMessage());

      // refused before anything happened: nothing saved, nothing planned
      Assertions.assertNull(BpmsInitiatedStartTest.WorkflowStartConfiguration.AGGREGATES.get("refused"));
      Assertions.assertTrue(plannedStartsByMessage().isEmpty(), "a refused start was planned");

    }

  }

  @Test
  @DisplayName("An adapter which reports no messages is not checked, and the start says so")
  public void anAdapterWithoutReportIsNotChecked(
      final CapturedOutput output) throws IOException {

    try (var testApp = buildTestApp(); var context = runTestApplication(
        testApp, NoReportedStartMessagesConfiguration.class)) {

      Assertions.assertTrue(
          output
              .getAll()
              .contains("Adapter 'test' does not say which messages start BPMN process 'TimerProcess' of "
                  + "workflow module 'test-module', "
                  + NOT_CHECKED),
          output.getAll());

      // goes to the BPMS as before, whatever the name
      startByMessage(context, "unchecked", "InvoicePaid");
      Assertions.assertEquals(1, plannedStartsByMessage().size());

    }

  }

  private static void startByMessage(
      final ConfigurableApplicationContext context,
      final String id,
      final String messageName) {

    @SuppressWarnings("unchecked")
    final var processService = (ProcessService<WorkflowStartAggregate>) context
        .getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class, WorkflowStartAggregate.class))
        .getObject();
    new TransactionTemplate(context.getBean(PlatformTransactionManager.class))
        .executeWithoutResult(status -> {
          final var aggregate = new WorkflowStartAggregate();
          aggregate.setId(id);
          processService.startWorkflowByMessage(aggregate, messageName);
        });

  }

  private static List<PhaseTwoCall> plannedStartsByMessage() {

    synchronized (TestPhaseTwoOutboxConfiguration.PLANNED) {
      return TestPhaseTwoOutboxConfiguration.PLANNED
          .stream()
          .filter(call -> call.args().containsKey(PhaseTwoCall.ARG_MESSAGE_NAME))
          .toList();
    }

  }

  private ConfigurableApplicationContext runTestApplication(
      final SpringBootTestApplication testApp,
      final Class<?> startEvents) {

    return testApp
        .applicationBuilder(
            DummyAdapterConfiguration.class,
            DummyAdapterProcessServiceConfiguration.class,
            WorkflowModuleAutoConfiguration.class,
            SpringBootMigrationAdapterAutoConfiguration.class,
            TestPersistenceConfiguration.class,
            TestPhaseTwoOutboxConfiguration.class,
            WorkflowStartWorkflowService.class,
            WorkflowModuleConfiguration.class,
            DeploymentTest.TestConfig.class,
            BpmsInitiatedStartTest.WorkflowStartConfiguration.class,
            TimerProcessIsDeployedConfiguration.class,
            startEvents)
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
