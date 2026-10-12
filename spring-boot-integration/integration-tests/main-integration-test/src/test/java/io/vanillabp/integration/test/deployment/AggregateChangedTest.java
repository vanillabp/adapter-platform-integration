package io.vanillabp.integration.test.deployment;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.ResolvableType;

import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.process.ProcessService;

/**
 * Acceptance test of pushing a changed workflow-aggregate to the BPMS with the dummy
 * adapter standing in for one. What it pins: both overloads are planned for the BPMS, and
 * the task id travels unchanged. A push is planned inside the caller's transaction, and
 * nothing reaches the adapter before that transaction ends.
 * <p>
 * The store of these tests remembers what was planned and dispatches nothing. It does not
 * take part in the transaction either. So these tests cannot show that a rollback removes
 * the entry, nor that the adapter receives the push. That is the job of a real store, and
 * the outbox integration tests check it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AggregateChangedTest {

  /**
   * Records what the dummy adapter was told to push.
   */
  static class RecordingPushes implements DummyPhaseTwoListener {

    final List<String> pushed = new LinkedList<>();

    @Override
    public void startedWorkflowPhaseTwo(
        final Object workflowAggregateId) {

    }

    @Override
    public void aggregateChanged(
        final Object workflowAggregateId,
        final String taskId,
        final boolean phaseTwo) {

      pushed.add("%s/%s/%s".formatted(workflowAggregateId, taskId, phaseTwo
          ? "phase-two"
          : "phase-one"));

    }

  }

  @Configuration
  static class PushConfiguration {

    @Bean
    javax.sql.DataSource pushDataSource() {

      return new org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder()
          .setType(org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    org.springframework.transaction.PlatformTransactionManager transactionManager(
        final javax.sql.DataSource pushDataSource) {

      return new org.springframework.jdbc.datasource.DataSourceTransactionManager(pushDataSource);

    }

    @Bean
    org.springframework.transaction.support.TransactionTemplate transactionTemplate(
        final org.springframework.transaction.PlatformTransactionManager transactionManager) {

      return new org.springframework.transaction.support.TransactionTemplate(transactionManager);

    }

    @Bean
    RecordingPushes recordingPushes() {

      return new RecordingPushes();

    }

    /**
     * The dummy knows the workflow. Otherwise the core would refuse to push to a BPMS
     * which never heard of it.
     */
    @Bean
    DummyTaskAwarenessSource activeWorkflow() {

      return (
          adapterId,
          workflowAggregateId,
          taskId) -> WorkflowAwareness.ACTIVE;

    }

    @Bean
    AggregatePersistenceAware<Aggregate> sampleAggregatePersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<Aggregate> getAggregateClass() {
          return Aggregate.class;
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public Aggregate save(
            final Aggregate workflowAggregate) {
          return workflowAggregate;
        }

        @Override
        public Object getAggregateId(
            final Aggregate workflowAggregate) {
          // the sample aggregate has no attributes. Every test runs its own
          // application, so one id is enough
          return "42";
        }

      };

    }

  }

  private static final String APPLICATION_YAML = """
      vanillabp:
        prioritized-adapters:
          - test
        adapters:
          test:
            type: dummy
            resources-location: classpath*:test-module/processes/dummy
      """;

  private ConfigurableApplicationContext runTestApplication(
      final SpringBootTestApplication testApp) {

    return testApp
        .applicationBuilder(
            DummyAdapterConfiguration.class,
            DummyAdapterProcessServiceConfiguration.class,
            WorkflowModuleAutoConfiguration.class,
            SpringBootMigrationAdapterAutoConfiguration.class,
            TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
            SampleWorkflowService.class,
            WorkflowModuleConfiguration.class,
            PushConfiguration.class)
        .run();

  }

  @SuppressWarnings("unchecked")
  @Test
  public void bothScopesReachTheBpms() throws IOException {

    try (var testApp = SpringBootTestApplication
        .builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build(); var context = runTestApplication(testApp)) {

      final var processService = (ProcessService<Aggregate>) context
          .getBeanProvider(
              ResolvableType.forClassWithGenerics(ProcessService.class, Aggregate.class))
          .getObject();

      final var aggregate = new Aggregate();

      TestPhaseTwoOutboxConfiguration.clear();
      context
          .getBean(org.springframework.transaction.support.TransactionTemplate.class)
          .executeWithoutResult(status -> {
            processService.aggregateChanged(aggregate);
            processService.aggregateChanged(aggregate, "task-1");
          });

      Assertions
          .assertEquals(
              java.util.Arrays.asList(null, "task-1"),
              TestPhaseTwoOutboxConfiguration.PLANNED
                  .stream()
                  .map(call -> call.args().get(io.vanillabp.integration.spi.PhaseTwoCall.ARG_TASK_ID))
                  .toList(),
              "both overloads have to reach the BPMS, and the task id has to travel unchanged");

    }

  }

  @SuppressWarnings("unchecked")
  @Test
  public void aPushIsPlannedInsideTheCallersTransaction() throws IOException {

    try (var testApp = SpringBootTestApplication
        .builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build(); var context = runTestApplication(testApp)) {

      final var processService = (ProcessService<Aggregate>) context
          .getBeanProvider(
              ResolvableType.forClassWithGenerics(ProcessService.class, Aggregate.class))
          .getObject();

      final var aggregate = new Aggregate();

      TestPhaseTwoOutboxConfiguration.clear();
      Assertions
          .assertThrows(
              IllegalStateException.class,
              () -> context
                  .getBean(org.springframework.transaction.support.TransactionTemplate.class)
                  .executeWithoutResult(status -> {
                    processService.aggregateChanged(aggregate);
                    // planned while the caller's transaction is still open. A store which
                    // writes into that transaction loses the entry with the rollback
                    Assertions
                        .assertEquals(
                            List.of(io.vanillabp.integration.spi.PhaseOperation.AGGREGATE_CHANGED.name()),
                            TestPhaseTwoOutboxConfiguration.PLANNED
                                .stream()
                                .map(io.vanillabp.integration.spi.PhaseTwoCall::operation)
                                .toList(),
                            "the push has to be planned inside the caller's transaction");
                    throw new IllegalStateException("the caller changed their mind");
                  }));

      // nothing reached the adapter. This store dispatches nothing, so this only shows
      // that the push did not go to the adapter directly, past the store
      Assertions
          .assertEquals(
              List.of(),
              context.getBean(RecordingPushes.class).pushed,
              "a push must not reach the BPMS before the caller's transaction committed");

    }

  }

  @SuppressWarnings("unchecked")
  @Test
  public void aTaskIdHasToBeATaskId() throws IOException {

    try (var testApp = SpringBootTestApplication
        .builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/dummy/DummyProcess.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build(); var context = runTestApplication(testApp)) {

      final var processService = (ProcessService<Aggregate>) context
          .getBeanProvider(
              ResolvableType.forClassWithGenerics(ProcessService.class, Aggregate.class))
          .getObject();

      final var aggregate = new Aggregate();

      final var exception = Assertions
          .assertThrows(
              IllegalArgumentException.class,
              () -> context
                  .getBean(org.springframework.transaction.support.TransactionTemplate.class)
                  .executeWithoutResult(status -> processService.aggregateChanged(aggregate, " ")));

      Assertions.assertTrue(exception.getMessage().contains("aggregateChanged(aggregate)"));

    }

  }

}
