package io.vanillabp.integration.test.processservice;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * A workflow service may declare more than one BPMN process id. Everything you can configure
 * per workflow, you can configure for each of these ids: the prioritized adapters, the outbox,
 * and the ids its leftovers were persisted under. The startup checks used to run for the
 * PRIMARY process only. So nobody looked at the id which a rename leaves behind, although
 * workflows still run under it.
 * <p>
 * This test covers the check whose failure hurts most. An aggregate may have a persisted
 * adapter id which names an adapter the configuration dropped. Without the check, this shows
 * up at the first operation and not at boot. And for a renamed process, the leftovers sit
 * under the SECONDARY id.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SecondaryProcessValidationTest {

  private static final String DEPLOYED_PROCESS = "DummyProcess";

  /**
   * The id the rename left behind. It is declared, but this boot deploys no model under it.
   */
  private static final String OLD_PROCESS = "OldDummyProcess";

  private static final String UNCONFIGURED_ADAPTER = "retired-bpms";

  public static class RenamedAggregate {

    String id = "4711";

  }

  /**
   * The application after a rename. This boot deploys the new id. The old id is still
   * declared, so the workflows which still run under it keep being served.
   */
  @Service
  @WorkflowService(
      workflowAggregateClass = RenamedAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = DEPLOYED_PROCESS),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = OLD_PROCESS))
  public static class RenamedWorkflowService {

    @WorkflowTask(taskDefinition = "processTask")
    public void processTask(
        final RenamedAggregate aggregate) {
    }

  }

  @Configuration
  static class AggregatePersistenceConfiguration {

    @Bean
    AggregatePersistenceAware<RenamedAggregate> renamedAggregatePersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<RenamedAggregate> getAggregateClass() {
          return RenamedAggregate.class;
        }

        @Override
        public RenamedAggregate save(
            final RenamedAggregate aggregate) {
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final RenamedAggregate aggregate) {
          return aggregate.id;
        }

      };

    }

  }

  /**
   * Stands in for the BPMN model of the deployed process: one task, which the workflow
   * service has the handler of.
   */
  @Configuration
  static class DeployedProcessWithOneTask {

    @Bean
    DummyTaskWiringSource taskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> DEPLOYED_PROCESS.equals(bpmnProcessId)
              ? List.of(new BpmnTaskSpec("Activity_Process", "processTask"))
              : List.of();

    }

  }

  /**
   * An outbox with calls of the OLD id. An adapter planned these calls, and the configuration
   * does not know this adapter any more. A migration which was given up halfway leaves this
   * behind, here under the id the rename left behind.
   */
  @Configuration
  static class OutboxHoldingCallsOfTheOldProcess {

    @Bean
    PhaseTwoOutbox outboxWithLeftoversOfTheOldProcess() {

      return new PhaseTwoOutbox() {

        @Override
        public boolean schedule(
            final PhaseTwoCall call) {
          return true;
        }

        @Override
        public Set<String> adapterIdsOfPendingCalls(
            final String workflowModuleId,
            final String bpmnProcessId) {
          return OLD_PROCESS.equals(bpmnProcessId)
              ? Set.of(UNCONFIGURED_ADAPTER)
              : Set.of();
        }

      };

    }

  }

  private SpringApplicationBuilder application() {

    return new SpringApplicationBuilder(
        DummyAdapterConfiguration.class, DummyAdapterProcessServiceConfiguration.class, WorkflowModuleAutoConfiguration.class, SpringBootMigrationAdapterAutoConfiguration.class, TestPersistenceConfiguration.class, TestTransactionRunnerConfiguration.class, WorkflowModuleConfiguration.class, DeploymentTest.TestConfig.class, AggregatePersistenceConfiguration.class, DeployedProcessWithOneTask.class, OutboxHoldingCallsOfTheOldProcess.class, RenamedWorkflowService.class);

  }

  @Test
  @DisplayName("The persisted-adapter-id check runs for a secondary BPMN process, naming it")
  public void aSecondaryProcessIsValidatedAsWell(
      final CapturedOutput output) {

    final var writtenBeforeThisBoot = output.getAll().length();

    try (var context = application().run()) {

      final var captured = output.getAll().substring(writtenBeforeThisBoot);
      Assertions.assertTrue(
          captured.contains("The adapter id '%s' is NOT configured any more".formatted(UNCONFIGURED_ADAPTER)),
          "the adapter id the outbox names was not reported at all: "
              + captured);
      Assertions.assertTrue(
          captured.contains("BPMN process '%s'".formatted(OLD_PROCESS)),
          "the report does not name the secondary BPMN process the leftovers sit under: "
              + captured);
      Assertions.assertTrue(
          captured.contains("waiting phase-two outbox entries"),
          "the report does not say what is left over: "
              + captured);

    }

  }

}
