package io.vanillabp.integration.test.delivery;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assertions;
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

/**
 * Acceptance test on Spring Boot for the inbound idempotency. The default delivery log based
 * on JDBC does the remembering. The dummy adapter delivers a task TWICE under the same
 * delivery identity, as a BPMS does which never learned the result. The
 * <code>&#64;WorkflowTask</code> method has to run once, and both deliveries get the same
 * outcome.
 * <p>
 * The test pins three more things. A delivery whose handler threw leaves no record, so its
 * retry runs the handler again. The switch on the task level turns the feature off. And two
 * deliveries of one task which overlap are named in the log and counted. A record written
 * after the work cannot prevent that case.
 */
@ExtendWith(SuppressOutputExtension.class)
public class InboundIdempotencyTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "DeliveryProcess";

  private static final String ADAPTER = "test";

  /**
   * In-memory persistence of the test aggregate, plus the transaction setup which the
   * delivery log runs on. It uses H2, so the records are really written and read back
   * through JDBC.
   */
  @Configuration
  static class DeliveryConfiguration {

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
          AGGREGATES.put(aggregate.getId(), copyOf(aggregate));
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
          final var stored = AGGREGATES.get(aggregateId);
          return stored != null
              ? copyOf(stored)
              : null;
        }

      };

    }

    private static DeliveryAggregate copyOf(
        final DeliveryAggregate aggregate) {

      final var copy = new DeliveryAggregate();
      copy.setId(aggregate.getId());
      copy.setStatus(aggregate.getStatus());
      copy.setInvocations(aggregate.getInvocations());
      copy.setCanceledTasks(aggregate.getCanceledTasks());
      return copy;

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

    /**
     * Stands in for the BPMN model. It lists the tasks of 'DeliveryProcess' which match
     * the handlers, so the wiring validation passes.
     */
    @Bean
    DummyTaskWiringSource deliveryTaskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> PROCESS.equals(bpmnProcessId)
              ? List.of(
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

  /**
   * The store of the delivery log uses the settings of the outbox. The task
   * 'undeduplicatedTask' switches the feature off for itself. This tests how that key is
   * resolved per task.
   */
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
                declared-aggregate-values: [ invocations ]
                allow-full-sync-with-bpms: true
                tasks:
                  undeduplicatedTask:
                    adapters:
                      test:
                        deduplicate-deliveries: false
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
            DeliveryConfiguration.class,
            // after the data source which it depends on. The auto-configuration is listed
            // as a plain source here, so its conditions only see the bean definitions
            // registered before it
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
   * One delivery of a task, as an adapter of a remote BPMS builds it. The delivery ID stays
   * the same when the BPMS repeats the delivery.
   */
  private TaskInvocationContext delivery(
      final String taskDefinition,
      final String aggregateId,
      final String deliveryId) {

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
      public String getDeliveryId() {
        return deliveryId;
      }

    };

  }

  private void storeAggregate(
      final String id) {

    final var aggregate = new DeliveryAggregate();
    aggregate.setId(id);
    aggregate.setStatus("new");
    DeliveryConfiguration.AGGREGATES.put(id, aggregate);

  }

  private int recordCount(
      final ConfigurableApplicationContext context,
      final String aggregateId) {

    return new JdbcTemplate(context.getBean(DataSource.class))
        .queryForObject(
            "SELECT COUNT(*) FROM %s WHERE AGGREGATE_ID = ?".formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
            Integer.class,
            aggregateId);

  }

  @Test
  public void repeatedDeliveriesRunTheHandlerOnce() throws IOException {

    DeliveryConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);

      // A redelivery answered from the record is counted. A rising rate means that the
      // BPMS hands out work again. The usual reason is a lock which is too short
      final var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
      context
          .getBean(io.vanillabp.integration.adapter.migration.observability.MicrometerVanillaBpMetrics.class)
          .bindTo(registry);

      // (a) the same delivery twice: handler once, both answered COMPLETED
      storeAggregate("4711");
      final var first = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-1"));
      final var second = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-1"));
      Assertions.assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, first.kind());
      Assertions.assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, second.kind());
      Assertions.assertEquals(1, DeliveryConfiguration.AGGREGATES.get("4711").getInvocations());
      Assertions.assertEquals(1, recordCount(context, "4711"));

      // the next task instance of the same workflow is another delivery
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-2"));
      Assertions.assertEquals(2, DeliveryConfiguration.AGGREGATES.get("4711").getInvocations());
      Assertions.assertEquals(2, recordCount(context, "4711"));

      // (b) a BPMN error is reported again with code and name, from the record
      storeAggregate("4712");
      final var error = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("raiseBpmnError", "4712", "job-3"));
      final var errorAgain = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("raiseBpmnError", "4712", "job-3"));
      Assertions.assertEquals(WorkflowTaskOutcome.Kind.BPMN_ERROR, errorAgain.kind());
      Assertions.assertEquals(error.errorCode(), errorAgain.errorCode());
      Assertions.assertEquals("PAYMENT_FAILED", errorAgain.errorCode());
      Assertions.assertEquals("PaymentFailed", errorAgain.errorName());
      Assertions.assertEquals(1, DeliveryConfiguration.AGGREGATES.get("4712").getInvocations());

      // (c) a handler which threw leaves no record: the transaction rolled back, so the
      // BPMS' retry reaches the handler again
      storeAggregate("4713");
      Assertions.assertThrows(
          IllegalStateException.class,
          () -> dummyAdapter.invokeTask(MODULE, PROCESS, delivery("failTask", "4713", "job-4")));
      Assertions.assertEquals(0, recordCount(context, "4713"));
      Assertions.assertThrows(
          IllegalStateException.class,
          () -> dummyAdapter.invokeTask(MODULE, PROCESS, delivery("failTask", "4713", "job-4")));
      Assertions.assertEquals(0, recordCount(context, "4713"));

      // (d) switched off for this task: nothing is remembered, and the handler runs for
      // every delivery. Every VanillaBP before this feature behaved like this
      storeAggregate("4714");
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("undeduplicatedTask", "4714", "job-5"));
      dummyAdapter.invokeTask(MODULE, PROCESS, delivery("undeduplicatedTask", "4714", "job-5"));
      Assertions.assertEquals(2, DeliveryConfiguration.AGGREGATES.get("4714").getInvocations());
      Assertions.assertEquals(0, recordCount(context, "4714"));

      // (e) what the metrics saw: exactly the two redeliveries answered from a record
      // (a) and (b), and nothing for the deliveries which ran the handler
      Assertions.assertEquals(
          2.0,
          registry
              .get(
                  io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.TASK_REDELIVERIES_DEDUPLICATED)
              .counters()
              .stream()
              .mapToDouble(io.micrometer.core.instrument.Counter::count)
              .sum(),
          "a repeated delivery answered from the record is what this meter counts");

    }

  }

  /**
   * The case which the record cannot catch. The BPMS hands out the task again while the
   * first handler is still running. So neither delivery finds a record, and the
   * <code>&#64;WorkflowTask</code> method runs twice. One record is written. The delivery
   * which lost the race makes the overlap visible.
   */
  @Test
  public void twoDeliveriesAtTheSameTimeAreNamedAndCounted(
      final io.vanillabp.integration.test.utils.CapturedOutput captured) throws Exception {

    DeliveryConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);

      final var registry = new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
      context
          .getBean(io.vanillabp.integration.adapter.migration.observability.MicrometerVanillaBpMetrics.class)
          .bindTo(registry);

      final var firstHandlerIsRunning = new CountDownLatch(1);
      final var secondDeliveryCommitted = new CountDownLatch(1);
      final var handlerRuns = new AtomicInteger();
      // the first delivery stays in the handler until the second one is through. A real
      // BPMS produces this overlap when its lock is too short
      DeliveryWorkflowService.WHILE_THE_CONCURRENT_TASK_RUNS.set(() -> {
        if (handlerRuns.incrementAndGet() > 1) {
          return;
        }
        firstHandlerIsRunning.countDown();
        try {
          secondDeliveryCommitted.await(30, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
          Thread
              .currentThread()
              .interrupt();
        }
      });

      try {

        storeAggregate("4715");
        final var heldDelivery = new Thread(
            () -> dummyAdapter.invokeTask(MODULE, PROCESS, delivery("concurrentTask", "4715", "job-6")));
        heldDelivery.start();
        Assertions.assertTrue(
            firstHandlerIsRunning.await(30, TimeUnit.SECONDS),
            "the first delivery never reached the handler");

        dummyAdapter.invokeTask(MODULE, PROCESS, delivery("concurrentTask", "4715", "job-6"));
        secondDeliveryCommitted.countDown();
        heldDelivery.join(TimeUnit.SECONDS.toMillis(30));

        Assertions.assertEquals(2, handlerRuns.get(), "both deliveries have to run the handler");
        Assertions.assertEquals(1, recordCount(context, "4715"), "one delivery key, one record");

      } finally {
        DeliveryWorkflowService.WHILE_THE_CONCURRENT_TASK_RUNS.set(() -> {
        });
      }

      Assertions.assertTrue(
          captured
              .getAll()
              .contains("were processed at the SAME time"),
          () -> "the overlap has to be said out loud: "
              + captured.getAll());
      Assertions.assertTrue(
          captured
              .getAll()
              .contains("test|test-module|DeliveryProcess|CREATED|job-6"),
          "the WARN names the delivery key, the adapter and the workflow");

      Assertions.assertEquals(
          1.0,
          registry
              .get(
                  io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.TASK_REDELIVERIES_CONCURRENT)
              .tag(
                  io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.TAG_TASK_DEFINITION,
                  "concurrentTask")
              .counter()
              .count(),
          "the delivery which found the key taken is what this meter counts");

    }

  }

}
