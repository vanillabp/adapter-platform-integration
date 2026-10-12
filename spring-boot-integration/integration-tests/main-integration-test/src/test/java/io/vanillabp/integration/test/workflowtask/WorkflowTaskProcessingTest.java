package io.vanillabp.integration.test.workflowtask;

import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterOverlayProperties;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestPhaseTwoOutboxConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.springboot.SpringBootTestApplication;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import lombok.Getter;

/**
 * Acceptance test of <code>&#64;WorkflowTask</code> processing. The dummy adapter stands
 * in for a BPMS. It triggers task calls through the core's {@code WorkflowTaskInvoker}.
 * The invoker loads the aggregate, runs the handler in a transaction and saves the
 * aggregate. It maps the three outcomes of the restored V1 contract: completed; BPMN
 * error by {@code TaskException}, with COMMITTED aggregate changes; failure, with the
 * changes rolled back. Also covered: parameter binding (aggregate, {@code @TaskParam},
 * multi-instance), asynchronous tasks ({@code @TaskId} stays open), the wiring
 * validation of a BPMN task without a method with its guiding message, and the task-level
 * adapter-configuration resolution from real application config. The other direction of
 * the wiring validation, a method without a BPMN task, is the business of
 * {@code OrphanWorkflowTaskMethodTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowTaskProcessingTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TaskProcess";

  /**
   * In-memory persistence for the test aggregate, with {@code loadById}, so tasks can be
   * processed. Plus the transaction setup which handlers need: a
   * {@link DataSourceTransactionManager} on H2. This end-to-end test with the dummy only
   * sees whether the aggregate was saved or not. The tests of the BPMS adapters pin down
   * how a real database rolls back.
   */
  @Configuration
  static class TaskProcessingConfiguration {

    static final Map<String, TaskProcessingAggregate> AGGREGATES = new ConcurrentHashMap<>();

    @Bean
    AggregatePersistenceAware<TaskProcessingAggregate> taskProcessingPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<TaskProcessingAggregate> getAggregateClass() {
          return TaskProcessingAggregate.class;
        }

        @Override
        public TaskProcessingAggregate save(
            final TaskProcessingAggregate aggregate) {
          AGGREGATES.put(aggregate.getId(), copyOf(aggregate));
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final TaskProcessingAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public TaskProcessingAggregate loadById(
            final Object aggregateId) {
          final var stored = AGGREGATES.get(aggregateId);
          return stored != null
              ? copyOf(stored)
              : null;
        }

      };

    }

    /**
     * Copies aggregates on save and load, so changes which were not saved never reach the
     * store. This works like a real persistence, where only saved state survives.
     */
    private static TaskProcessingAggregate copyOf(
        final TaskProcessingAggregate aggregate) {

      final var copy = new TaskProcessingAggregate();
      copy.setId(aggregate.getId());
      copy.setStatus(aggregate.getStatus());
      copy.setTaskId(aggregate.getTaskId());
      copy.setEvent(aggregate.getEvent());
      copy.setElement(aggregate.getElement());
      copy.setIndex(aggregate.getIndex());
      copy.setTotal(aggregate.getTotal());
      return copy;

    }

    @Bean
    DataSource taskProcessingDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource taskProcessingDataSource) {

      return new DataSourceTransactionManager(taskProcessingDataSource);

    }

    /**
     * Stands in for the BPMN model. It supplies the tasks of 'TaskProcess' which match the
     * handlers, so the wiring validation passes. The other processes of the module have no
     * tasks.
     */
    @Bean
    DummyTaskWiringSource taskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> PROCESS.equals(bpmnProcessId)
              ? List.of(
                  new BpmnTaskSpec("Activity_Process", "processTask"),
                  new BpmnTaskSpec("Activity_Error", "raiseBpmnError"),
                  new BpmnTaskSpec("Activity_Fail", "failTask"),
                  new BpmnTaskSpec("Activity_Async", "asyncTask"),
                  new BpmnTaskSpec("Activity_Bind", "bindParameters"),
                  new BpmnTaskSpec("Activity_Mdc", "recordMdc"),
                  new BpmnTaskSpec("Activity_MdcFail", "recordMdcAndFail"))
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
                resources-location: classpath*:test-module/processes/workflowtask
            workflows:
              TaskProcess:
                declared-aggregate-values: [ element, event, index, total ]
                tasks:
                  processTask:
                    adapters:
                      test:
                        test: 42
      """;

  private ConfigurableApplicationContext runTestApplication(
      final SpringBootTestApplication testApp,
      final Class<?>... additionalClasses) {

    final var classes = new java.util.LinkedList<Class<?>>(List.of(
        DummyAdapterConfiguration.class,
        DummyAdapterProcessServiceConfiguration.class,
        WorkflowModuleAutoConfiguration.class,
        SpringBootMigrationAdapterAutoConfiguration.class,
        TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
        TaskProcessingWorkflowService.class,
        WorkflowModuleConfiguration.class,
        TaskProcessingConfiguration.class,
        DeploymentTest.TestConfig.class));
    classes.addAll(List.of(additionalClasses));
    return testApp.applicationBuilder(classes.toArray(Class[]::new)).run();

  }

  private SpringBootTestApplication buildTestApp() throws IOException {

    return SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

  }

  private TaskInvocationContext context(
      final String taskDefinition,
      final String aggregateId) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

    };

  }

  private TaskProcessingAggregate storeAggregate(
      final String id) {

    final var aggregate = new TaskProcessingAggregate();
    aggregate.setId(id);
    aggregate.setStatus("new");
    TaskProcessingConfiguration.AGGREGATES.put(id, aggregate);
    return aggregate;

  }

  @Test
  public void taskProcessingCoversAllOutcomesAndBindings(
      final CapturedOutput output) throws IOException {

    TaskProcessingConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      // the trigger on the adapter side: the deployment-service bean of the adapter id.
      // A real BPMS adapter also hands a delivered task to the core this way
      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);

      // (a) normal return: task completed, aggregate changes saved
      storeAggregate("4711");
      final var completed = dummyAdapter.invokeTask(MODULE, PROCESS, context("processTask", "4711"));
      Assertions.assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, completed.kind());
      Assertions.assertEquals(
          "processed",
          TaskProcessingConfiguration.AGGREGATES.get("4711").getStatus());

      // (b) TaskException: BPMN error outcome, aggregate changes COMMITTED anyway
      storeAggregate("4712");
      final var bpmnError = dummyAdapter.invokeTask(MODULE, PROCESS, context("raiseBpmnError", "4712"));
      Assertions.assertEquals(WorkflowTaskOutcome.Kind.BPMN_ERROR, bpmnError.kind());
      Assertions.assertEquals("PAYMENT_FAILED", bpmnError.errorCode());
      Assertions.assertEquals("PaymentFailed", bpmnError.errorName());
      Assertions.assertEquals(
          "bpmn-error-raised",
          TaskProcessingConfiguration.AGGREGATES.get("4712").getStatus());

      // (c) any other exception: it is passed on, the task is NOT completed, and the
      // aggregate changes are NOT saved (the transaction is rolled back)
      storeAggregate("4713");
      final var failure = Assertions.assertThrows(
          IllegalStateException.class,
          () -> dummyAdapter.invokeTask(MODULE, PROCESS, context("failTask", "4713")));
      Assertions.assertEquals("something broke", failure.getMessage());
      Assertions.assertEquals(
          "new",
          TaskProcessingConfiguration.AGGREGATES.get("4713").getStatus());

      // asynchronous task (@TaskId): it stays open and receives the task id of the BPMS
      storeAggregate("4714");
      final var pending = dummyAdapter.invokeTask(MODULE, PROCESS, new TaskInvocationContext() {

        @Override
        public String getTaskDefinition() {
          return "asyncTask";
        }

        @Override
        public String getWorkflowAggregateId() {
          return "4714";
        }

        @Override
        public String getTaskId() {
          return "task-0815";
        }

      });
      Assertions.assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, pending.kind());
      Assertions.assertEquals(
          "task-0815",
          TaskProcessingConfiguration.AGGREGATES.get("4714").getTaskId());

      // parameter binding: @TaskParam and @MultiInstance* values from the context
      storeAggregate("4715");
      final var multiInstances = new LinkedHashMap<String, MultiInstanceValue>();
      multiInstances.put("items", new MultiInstanceValue("item-3", 3, 7));
      dummyAdapter.invokeTask(MODULE, PROCESS, new TaskInvocationContext() {

        @Override
        public String getTaskDefinition() {
          return "bindParameters";
        }

        @Override
        public String getWorkflowAggregateId() {
          return "4715";
        }

        @Override
        public Object getTaskParameter(
            final String name) {
          return "approval".equals(name)
              ? "APPROVED"
              : null;
        }

        @Override
        public Map<String, MultiInstanceValue> getMultiInstances() {
          return multiInstances;
        }

      });
      final var bound = TaskProcessingConfiguration.AGGREGATES.get("4715");
      Assertions.assertEquals("APPROVED", bound.getStatus());
      Assertions.assertEquals(3, bound.getIndex());
      Assertions.assertEquals(7, bound.getTotal());
      Assertions.assertEquals("item-3", bound.getElement());

      // adapter configuration at task level, from a REAL application config: the most
      // specific value wins (42 at the task beats 1 at the adapter)
      final var overlay = context.getBean(DummyAdapterOverlayProperties.class);
      Assertions.assertEquals(42, overlay.testFor(MODULE, PROCESS, "processTask", "test"));
      Assertions.assertEquals(1, overlay.testFor(MODULE, PROCESS, "someOtherTask", "test"));
      Assertions.assertEquals(1, overlay.testFor(null, null, null, "test"));

    }

  }

  /**
   * Supplies a task the workflow service does not implement. The boot must fail with the
   * guiding wiring message. The reverse direction, methods which match no task, is
   * validated per module at the end of deployResources.
   */
  @Configuration
  static class BrokenWiringConfiguration {

    @Bean
    DummyTaskWiringSource brokenTaskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> PROCESS.equals(bpmnProcessId)
              ? List.of(new BpmnTaskSpec("Activity_Unknown", "notImplemented"))
              : List.of();

    }

  }

  /**
   * An aggregate whose sync model is ambiguous. Its attributes are annotated both ways,
   * and the class states no mode of its own.
   */
  @Getter
  public static class AmbiguousSyncAggregate {

    @io.vanillabp.spi.service.SyncWithBPMS
    private String customerName;

    @io.vanillabp.spi.service.NoSyncWithBPMS
    private String creditCardNumber;

  }

  @Test
  public void aggregateSyncIsProvidedAndValidatedByThePlatform() throws IOException {

    TaskProcessingConfiguration.AGGREGATES.clear();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      final var dummyAdapter = context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);
      final var invoker = context.getBean(
          io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry.class);

      // the @WorkflowTask method changed the aggregate. An adapter which completes the
      // task afterwards asks the core for the values shared with its BPMS. The aggregate
      // is loaded in its OWN transaction, because the transaction of the task is committed
      storeAggregate("4720");
      dummyAdapter.invokeTask(MODULE, PROCESS, context("processTask", "4720"));

      final var shared = invoker.syncedWorkflowAggregateValues(
          MODULE,
          PROCESS,
          "4720",
          io.vanillabp.integration.adapter.spi.AggregateSyncMode.FULL);
      Assertions.assertEquals("processed", shared.get("status"), "the value the task produced is shared");
      Assertions.assertEquals("4720", shared.get("id"));
      Assertions.assertFalse(
          shared.containsKey("taskId"),
          "a @NoSyncWithBPMS attribute is never shared");

      // an unknown BPMN process or aggregate never breaks a task completion
      Assertions.assertEquals(
          Map.of(),
          invoker.syncedWorkflowAggregateValues(
              MODULE, "UnknownProcess", "4720", io.vanillabp.integration.adapter.spi.AggregateSyncMode.FULL));

      // the platform offers the model as a bean and validates aggregates with it
      final var syncModel = context.getBean(io.vanillabp.integration.adapter.spi.WorkflowAggregateSync.class);
      final var ambiguous = Assertions.assertThrows(
          IllegalStateException.class,
          () -> syncModel.validateSyncModel(AmbiguousSyncAggregate.class));
      Assertions.assertTrue(
          ambiguous.getMessage().contains("does not state its own mode"),
          "unexpected message: "
              + ambiguous.getMessage());
      Assertions.assertTrue(
          ambiguous.getMessage().contains("'creditCardNumber'"),
          "unexpected message: "
              + ambiguous.getMessage());

    }

  }

  @Test
  public void incompleteWiringFailsTheBootWithGuidingMessages() throws IOException {

    TaskProcessingConfiguration.AGGREGATES.clear();

    try (var testApp = SpringBootTestApplication.builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build()) {

      final var failure = Assertions.assertThrows(
          RuntimeException.class,
          () -> {
            final var classes = new java.util.LinkedList<Class<?>>(List.of(
                DummyAdapterConfiguration.class,
                DummyAdapterProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class,
                TestPersistenceConfiguration.class, TestPhaseTwoOutboxConfiguration.class,
                TaskProcessingWorkflowService.class,
                WorkflowModuleConfiguration.class,
                BrokenWiringConfiguration.class,
                DeploymentTest.TestConfig.class));
            // persistence and transaction setup, but without the correct wiring source
            classes.add(TaskProcessingConfigurationWithoutWiringSource.class);
            testApp.applicationBuilder(classes.toArray(Class[]::new)).run().close();
          });

      final var message = rootMessage(failure);
      // a BPMN task without a method, with the fix, and the methods which were found. A
      // method without a task is reported by a later check, which this boot never reaches
      Assertions.assertTrue(
          message.contains("Task wiring of BPMN process 'TaskProcess' of workflow module 'test-module'"),
          "unexpected message: "
              + message);
      Assertions.assertTrue(
          message.contains("'Activity_Unknown'"),
          "unexpected message: "
              + message);
      Assertions.assertTrue(
          message.contains("@WorkflowTask(taskDefinition = \"notImplemented\")"),
          "unexpected message: "
              + message);
      Assertions.assertTrue(
          message.contains("@WorkflowTask methods found: ") && message.contains("processTask"),
          "unexpected message: "
              + message);

    }

  }

  /**
   * The same persistence and transaction beans as {@link TaskProcessingConfiguration}, but
   * WITHOUT the wiring source. {@link BrokenWiringConfiguration} provides the broken one.
   */
  @Configuration
  static class TaskProcessingConfigurationWithoutWiringSource {

    @Bean
    AggregatePersistenceAware<TaskProcessingAggregate> taskProcessingPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<TaskProcessingAggregate> getAggregateClass() {
          return TaskProcessingAggregate.class;
        }

        @Override
        public TaskProcessingAggregate save(
            final TaskProcessingAggregate aggregate) {
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final TaskProcessingAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public TaskProcessingAggregate loadById(
            final Object aggregateId) {
          return null;
        }

      };

    }

    @Bean
    DataSource taskProcessingDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource taskProcessingDataSource) {

      return new DataSourceTransactionManager(taskProcessingDataSource);

    }

  }

  private static String rootMessage(
      final Throwable throwable) {

    var cause = throwable;
    final var messages = new HashMap<Integer, String>();
    var depth = 0;
    while (cause != null) {
      messages.put(depth++, String.valueOf(cause.getMessage()));
      if (cause.getCause() == cause) {
        break;
      }
      cause = cause.getCause();
    }
    return messages.get(depth - 1);

  }

}
