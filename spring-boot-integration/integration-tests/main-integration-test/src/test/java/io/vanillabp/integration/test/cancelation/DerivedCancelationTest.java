package io.vanillabp.integration.test.cancelation;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLogAutoConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.service.WorkflowEnd;

/**
 * Acceptance test on Spring Boot: an adapter reports the end of a workflow and names it,
 * and every task the core still believes is open in that workflow reaches the application
 * as canceled before <code>&#64;WorkflowEnded</code> runs.
 * <p>
 * The cases which must NOT derive are here too, because they are what keeps this additive:
 * an end which names no workflow, and a record which belongs to another adapter.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DerivedCancelationTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "CancelProcess";

  private static final String ADAPTER = "test";

  /**
   * In-memory persistence of the test aggregate plus the H2 database the delivery log
   * writes its records into. The aggregate is copied on save and on load, so a handler
   * which threw leaves nothing of what it changed behind.
   */
  @Configuration
  static class CancelConfiguration {

    static final Map<String, CancelAggregate> AGGREGATES = new ConcurrentHashMap<>();

    @Bean
    AggregatePersistenceAware<CancelAggregate> cancelPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<CancelAggregate> getAggregateClass() {
          return CancelAggregate.class;
        }

        @Override
        public CancelAggregate save(
            final CancelAggregate aggregate) {
          AGGREGATES.put(aggregate.getId(), copyOf(aggregate));
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final CancelAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public CancelAggregate loadById(
            final Object aggregateId) {
          final var stored = AGGREGATES.get(aggregateId);
          return stored != null
              ? copyOf(stored)
              : null;
        }

      };

    }

    private static CancelAggregate copyOf(
        final CancelAggregate aggregate) {

      final var copy = new CancelAggregate();
      copy.setId(aggregate.getId());
      copy.setWhatArrived(aggregate.getWhatArrived());
      return copy;

    }

    @Bean
    DataSource cancelDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource cancelDataSource) {

      return new DataSourceTransactionManager(cancelDataSource);

    }

    @Bean
    DummyTaskWiringSource cancelTaskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> PROCESS.equals(bpmnProcessId)
              ? List
                  .of(
                      new BpmnTaskSpec("Activity_Signature", "awaitSignature"),
                      new BpmnTaskSpec("Activity_Approval", "awaitApproval"),
                      new BpmnTaskSpec("Activity_Payment", "awaitPayment"),
                      new BpmnTaskSpec("Activity_Delivery", "awaitDelivery"))
              : List.of();

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
                resources-location: classpath*:test-module/processes/cancelation
            workflows:
              CancelProcess:
                allow-full-sync-with-bpms: true
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
            CancelWorkflowService.class,
            WorkflowModuleConfiguration.class,
            CancelConfiguration.class,
            JdbcTaskDeliveryLogAutoConfiguration.class,
            DeploymentTest.TestConfig.class)
        .run();

  }

  private SpringBootTestApplication buildTestApp() throws IOException {

    return SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

  }

  private TaskInvocationContext delivery(
      final String taskDefinition,
      final String aggregateId,
      final String taskId) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public String getTaskId() {
        return taskId;
      }

      @Override
      public String getDeliveryId() {
        return "job-of-"
            + taskId;
      }

      @Override
      public String getWorkflowId() {
        return "workflow-of-"
            + aggregateId;
      }

    };

  }

  /**
   * The notification a real adapter builds when a workflow ended.
   *
   * @param aggregateId The aggregate of the ended workflow
   * @param workflowId What the BPMS calls that workflow, or <code>null</code> where the
   *          adapter names none
   */
  private WorkflowEndedContext workflowEnded(
      final String aggregateId,
      final String workflowId) {

    return new WorkflowEndedContext() {

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public String getWorkflowId() {
        return workflowId;
      }

      @Override
      public WorkflowEnd.Kind getKind() {
        // COMPLETED on purpose: a terminate end event ends a Camunda 8 instance this way
        // while taking open tasks with it, so the kind must not decide
        return WorkflowEnd.Kind.COMPLETED;
      }

      @Override
      public Instant getEndTime() {
        return Instant.now();
      }

    };

  }

  private void storeAggregate(
      final String id) {

    final var aggregate = new CancelAggregate();
    aggregate.setId(id);
    CancelConfiguration.AGGREGATES.put(id, aggregate);

  }

  private int openRecordCount(
      final ConfigurableApplicationContext context,
      final String aggregateId) {

    return new JdbcTemplate(context.getBean(DataSource.class))
        .queryForObject(
            """
                SELECT COUNT(*) FROM %s WHERE AGGREGATE_ID = ? AND OUTCOME = 'COMPLETION_PENDING' \
                AND TASK_CLOSED_AT IS NULL"""
                .formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
            Integer.class,
            aggregateId);

  }

  @Test
  @DisplayName("Every task open in an ended workflow is reported as canceled, and then the end is")
  public void theEndOfAWorkflowCancelsWhatItWasWaitingFor() throws IOException {

    CancelConfiguration.AGGREGATES.clear();
    CancelWorkflowService.CANCELATIONS_WHICH_FAIL.set(0);

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);

      storeAggregate("4711");
      Assertions
          .assertEquals(
              WorkflowTaskOutcome.Kind.COMPLETION_PENDING,
              dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitSignature", "4711", "task-1")).kind());
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitApproval", "4711", "task-2"));
      Assertions.assertEquals(2, openRecordCount(context, "4711"));

      dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4711", "workflow-of-4711"));

      Assertions
          .assertEquals(
              "task-1,task-2,ended",
              CancelConfiguration.AGGREGATES.get("4711").getWhatArrived(),
              "both tasks reach the application as canceled, oldest first, and the end runs after them");
      Assertions
          .assertEquals(
              0,
              openRecordCount(context, "4711"),
              "and both records are closed, so nothing derives them a second time");

      // the same notification once more, which is what at-least-once means: the records
      // are closed, so nothing is reported again
      dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4711", "workflow-of-4711"));
      Assertions
          .assertEquals(
              "task-1,task-2,ended,ended",
              CancelConfiguration.AGGREGATES.get("4711").getWhatArrived(),
              "the end is reported again, the cancellations are not");

    }

  }

  @Test
  @DisplayName("An end which names no workflow derives nothing, and a record of another adapter is left alone")
  public void whatMustNotBeDerived() throws IOException {

    CancelConfiguration.AGGREGATES.clear();
    CancelWorkflowService.CANCELATIONS_WHICH_FAIL.set(0);

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);
      final var deliveryLog = context.getBean(JdbcTaskDeliveryLog.class);

      // (a) an adapter which names no workflow keeps behaving as it did
      storeAggregate("4712");
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitSignature", "4712", "task-3"));
      dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4712", null));
      Assertions
          .assertEquals(
              "ended",
              CancelConfiguration.AGGREGATES.get("4712").getWhatArrived(),
              "nothing is derived where the notification names no workflow");
      Assertions.assertEquals(1, openRecordCount(context, "4712"), "so the record stays open");

      // (b) a record which another BPMS wrote belongs to that BPMS
      storeAggregate("4713");
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitSignature", "4713", "task-4"));
      new org.springframework.transaction.support.TransactionTemplate(
          context.getBean(PlatformTransactionManager.class))
          .executeWithoutResult(
              status -> deliveryLog
                  .record(
                      new TaskDelivery("another-bpms|delivery", "another-bpms", MODULE, PROCESS, "4713", "workflow-of-4713", "awaitApproval", "Activity_Approval", "task-5", "COMPLETION_PENDING", null, null, Instant
                          .now(), null)));
      Assertions.assertEquals(2, openRecordCount(context, "4713"));

      dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4713", "workflow-of-4713"));

      Assertions
          .assertEquals(
              "task-4,ended",
              CancelConfiguration.AGGREGATES.get("4713").getWhatArrived(),
              "only the task this adapter delivered is reported");
      Assertions
          .assertEquals(
              1,
              openRecordCount(context, "4713"),
              "the record of the other BPMS stays open - this end says nothing about it");

    }

  }

  /**
   * A derived cancellation carries no job of the element, so a value the method reads
   * from it is absent. The boot names exactly the methods which read one, because a value
   * which is silently missing is what an application finds out in production.
   */
  @Test
  @DisplayName("The boot names the methods a derived cancellation cannot fill")
  public void theBootNamesWhatACancelationCannotCarry(
      final io.vanillabp.integration.test.utils.CapturedOutput captured) throws IOException {

    CancelConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      Assertions
          .assertTrue(
              captured.getAll().contains("subscribe to TaskEvent.Event.CANCELED and read values"),
              () -> "the boot has to say what a derived cancellation cannot fill: "
                  + captured.getAll());
      Assertions
          .assertTrue(
              captured.getAll().contains("awaitPayment' reads @TaskParam amount"),
              () -> "the method reading @TaskParam has to be named with what it reads: "
                  + captured.getAll());
      Assertions
          .assertFalse(
              captured.getAll().contains("awaitSignature' reads"),
              () -> "a method which reads nothing of the element is not affected: "
                  + captured.getAll());
      Assertions
          .assertFalse(
              captured.getAll().contains("awaitDelivery' reads"),
              () -> "and neither is one which never asked for the event: "
                  + captured.getAll());

    }

  }

  @Test
  @DisplayName("A handler which throws leaves the record open, and the next end reports the task again")
  public void aHandlerWhichThrowsLeavesTheRecordOpen() throws IOException {

    CancelConfiguration.AGGREGATES.clear();
    CancelWorkflowService.CANCELATIONS_WHICH_FAIL.set(1);

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);

      storeAggregate("4714");
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitSignature", "4714", "task-6"));

      dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4714", "workflow-of-4714"));

      Assertions
          .assertEquals(
              "ended",
              CancelConfiguration.AGGREGATES.get("4714").getWhatArrived(),
              "the handler threw, so nothing of that delivery was written - the end still ran");
      Assertions
          .assertEquals(
              1,
              openRecordCount(context, "4714"),
              "the claim went back with the transaction, so the record is open again");

      dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4714", "workflow-of-4714"));

      Assertions
          .assertEquals(
              "ended,task-6,ended",
              CancelConfiguration.AGGREGATES.get("4714").getWhatArrived(),
              "the second run derives the task again, which is what at-least-once means");
      Assertions.assertEquals(0, openRecordCount(context, "4714"));

    }

  }

}
