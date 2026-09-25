package io.vanillabp.integration.test.delivery;

import java.io.IOException;
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
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLogAutoConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.service.TaskEvent;

/**
 * Acceptance test on Spring Boot, with the default JDBC-based delivery log: a task the
 * BPMS cancels leaves ONE record and that record is closed.
 * <p>
 * Both halves of it are read from the table. The record the task left open is stamped by
 * the cancelling delivery itself, and that delivery writes no second OPEN record although
 * the method carries a <code>&#64;TaskId</code> parameter. A method which does not
 * subscribe to the cancellation is the case which must not change: its record stays open,
 * because nothing told it the task is over.
 */
@ExtendWith(SuppressOutputExtension.class)
public class CanceledTaskTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "DeliveryProcess";

  private static final String ADAPTER = "test";

  /**
   * In-memory persistence of the test aggregate plus the H2 database the delivery log
   * writes its records into - the shape the other delivery tests use.
   */
  @Configuration
  static class CancelConfiguration {

    static final Map<String, DeliveryAggregate> AGGREGATES = new ConcurrentHashMap<>();

    @Bean
    AggregatePersistenceAware<DeliveryAggregate> deliveryPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<DeliveryAggregate> getAggregateClass() {
          return DeliveryAggregate.class;
        }

        @Override
        public DeliveryAggregate save(
            final DeliveryAggregate aggregate) {
          AGGREGATES.put(aggregate.getId(), aggregate);
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final DeliveryAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public DeliveryAggregate loadById(
            final Object aggregateId) {
          return AGGREGATES.get(aggregateId);
        }

      };

    }

    @Bean
    DataSource deliveryDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource deliveryDataSource) {

      return new DataSourceTransactionManager(deliveryDataSource);

    }

    @Bean
    DummyTaskWiringSource deliveryTaskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> PROCESS.equals(bpmnProcessId)
              ? List
                  .of(
                      new BpmnTaskSpec("Activity_Process", "processTask"),
                      new BpmnTaskSpec("Activity_Error", "raiseBpmnError"),
                      new BpmnTaskSpec("Activity_Fail", "failTask"),
                      new BpmnTaskSpec("Activity_Undeduplicated", "undeduplicatedTask"),
                      new BpmnTaskSpec("Activity_Await", "awaitCompletion"),
                      new BpmnTaskSpec("Activity_Cancelable", "cancelableTask"),
                      new BpmnTaskSpec("Activity_Concurrent", "concurrentTask"))
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
                resources-location: classpath*:test-module/processes/delivery
            workflows:
              DeliveryProcess:
                allow-full-sync-with-bpms: true
                declared-aggregate-values: [ "*" ]
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
            DeliveryWorkflowService.class,
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

  /**
   * One delivery of one task. The delivery id carries the event as well, the way an
   * adapter which activates a job per event builds it.
   */
  private TaskInvocationContext delivery(
      final String taskDefinition,
      final String aggregateId,
      final String taskId,
      final TaskEvent.Event event) {

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
        return taskId;
      }

      @Override
      public TaskEvent.Event getTaskEvent() {
        return event;
      }

      @Override
      public String getWorkflowId() {
        return "workflow-of-"
            + aggregateId;
      }

    };

  }

  private void storeAggregate(
      final String id) {

    final var aggregate = new DeliveryAggregate();
    aggregate.setId(id);
    aggregate.setStatus("new");
    CancelConfiguration.AGGREGATES.put(id, aggregate);

  }

  private JdbcTemplate jdbc(
      final ConfigurableApplicationContext context) {

    return new JdbcTemplate(context.getBean(DataSource.class));

  }

  private int recordCount(
      final ConfigurableApplicationContext context,
      final String aggregateId) {

    return jdbc(context)
        .queryForObject(
            "SELECT COUNT(*) FROM %s WHERE AGGREGATE_ID = ?".formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
            Integer.class,
            aggregateId);

  }

  private int openRecordCount(
      final ConfigurableApplicationContext context,
      final String aggregateId) {

    return jdbc(context)
        .queryForObject(
            """
                SELECT COUNT(*) FROM %s WHERE AGGREGATE_ID = ? AND OUTCOME = 'COMPLETION_PENDING' \
                AND TASK_CLOSED_AT IS NULL"""
                .formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
            Integer.class,
            aggregateId);

  }

  private int recordsWithoutAClosingMoment(
      final ConfigurableApplicationContext context,
      final String aggregateId) {

    return jdbc(context)
        .queryForObject(
            "SELECT COUNT(*) FROM %s WHERE AGGREGATE_ID = ? AND TASK_CLOSED_AT IS NULL"
                .formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
            Integer.class,
            aggregateId);

  }

  @Test
  @DisplayName("A task the BPMS cancels leaves one closed record, and a method not asking for the event does not")
  public void aCancellationClosesTheRecordOfItsTask() throws IOException {

    CancelConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);
      final var deliveryLog = context.getBean(JdbcTaskDeliveryLog.class);

      // (a) a user task handed to the application, which leaves it open
      storeAggregate("4711");
      final var created = dummyAdapter
          .invokeTask(MODULE, PROCESS, delivery("cancelableTask", "4711", "task-1", TaskEvent.Event.CREATED));
      Assertions.assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, created.kind());
      Assertions.assertEquals(1, openRecordCount(context, "4711"));

      // (b) the BPMS takes the task away and says so
      final var canceled = dummyAdapter
          .invokeTask(MODULE, PROCESS, delivery("cancelableTask", "4711", "task-1", TaskEvent.Event.CANCELED));
      Assertions
          .assertEquals(
              WorkflowTaskOutcome.Kind.COMPLETED,
              canceled.kind(),
              "a canceled task is over, whatever the @TaskId parameter of the method promised");
      Assertions
          .assertEquals(
              "task-1",
              CancelConfiguration.AGGREGATES.get("4711").getCanceledTasks(),
              "the method asked for the event, so it was called with it");

      // what the table says afterwards: no open record left, and every record naming that
      // task carries the moment it was closed. Two rows, one per delivery: the
      // cancellation keeps a record of its own so a BPMS repeating it does not run the
      // method a second time
      Assertions
          .assertEquals(
              0,
              openRecordCount(context, "4711"),
              "the record the task left open is closed by the cancelling delivery");
      Assertions.assertEquals(2, recordCount(context, "4711"), "one record per delivery");
      Assertions
          .assertEquals(
              0,
              recordsWithoutAClosingMoment(context, "4711"),
              "and both of them carry the moment the task was closed");
      Assertions
          .assertTrue(
              deliveryLog.openTasksOfAggregate(MODULE, PROCESS, "4711").isEmpty(),
              "so nothing reads that workflow as waiting for a task any more");

      // (c) a method which never asked for the event: the delivery is skipped and the
      // record it left open stays exactly as it was
      storeAggregate("4712");
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitCompletion", "4712", "task-2", TaskEvent.Event.CREATED));
      Assertions.assertEquals(1, openRecordCount(context, "4712"));
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitCompletion", "4712", "task-2", TaskEvent.Event.CANCELED));
      Assertions
          .assertEquals(
              1,
              recordCount(context, "4712"),
              "a delivery nobody subscribed to is skipped before anything is written");
      Assertions
          .assertEquals(
              1,
              openRecordCount(context, "4712"),
              "so the record of that task stays open, and the next wake-up is what closes it");

    }

  }

  /**
   * The second record of one task is what {@code markTaskClosed} has to close as well:
   * a task cancelled after a record was written for it by another delivery id leaves two
   * rows naming the same task, and a row left open keeps the task alive for everything
   * which reads the open work.
   */
  @Test
  @DisplayName("Every record naming the task is closed, not only the newest one")
  public void everyRecordOfTheTaskIsClosed() throws IOException {

    CancelConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);
      final var deliveryLog = context.getBean(JdbcTaskDeliveryLog.class);

      // two deliveries of ONE task, under two delivery ids - which is what a BPMS
      // handing the same task out under a new job key produces
      storeAggregate("4713");
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitCompletion", "4713", "task-3", TaskEvent.Event.CREATED));
      dummyAdapter
          .invokeTask(
              MODULE,
              PROCESS,
              new TaskInvocationContext() {

                @Override
                public String getAdapterId() {
                  return ADAPTER;
                }

                @Override
                public String getTaskDefinition() {
                  return "awaitCompletion";
                }

                @Override
                public String getWorkflowAggregateId() {
                  return "4713";
                }

                @Override
                public String getTaskId() {
                  return "task-3";
                }

                @Override
                public String getDeliveryId() {
                  return "another-job-of-task-3";
                }

              });
      Assertions.assertEquals(2, openRecordCount(context, "4713"), "two records name the same task");

      Assertions
          .assertEquals(
              2,
              deliveryLog.markTaskClosed(MODULE, PROCESS, "4713", "task-3"),
              "both of them are closed by one call, and the count says how many it closed");
      Assertions.assertEquals(0, openRecordCount(context, "4713"));
      Assertions
          .assertEquals(
              0,
              deliveryLog.markTaskClosed(MODULE, PROCESS, "4713", "task-3"),
              "a second call finds nothing left to close, which is what lets a caller claim a task");

    }

  }

}
