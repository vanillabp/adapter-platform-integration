package io.vanillabp.integration.test.secondary;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
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
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;
import lombok.Setter;

/**
 * A called process is no workflow of its own, on Spring Boot: its start and its end do not
 * reach the application, and the workflow at the top ends once.
 * <p>
 * The workflow service serves {@code Ordering} and declares {@code Shipping}, which
 * {@code Ordering} calls, as a secondary process. It declares {@code Dispatching} as well,
 * the id a renamed process left behind, and no model arrives for it. The workflows the BPMS
 * still holds under that id were started under it, so they keep their start and their end.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ACalledProcessIsNoWorkflowTest {

  private static final String MODULE = "test-module";

  private static final String PRIMARY_PROCESS = "Ordering";

  private static final String CALLED_PROCESS = "Shipping";

  private static final String RENAMED_PROCESS = "Dispatching";

  private static final String ADAPTER = "test";

  private static final String AGGREGATE = "4711";

  @Getter
  @Setter
  public static class OrderAggregate {

    private String id;

    private String status;

  }

  /**
   * Every end the application was told about, as "aggregate|kind|process".
   */
  static final List<String> ENDS_REPORTED = new CopyOnWriteArrayList<>();

  @Service
  @WorkflowService(
      workflowAggregateClass = OrderAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PRIMARY_PROCESS),
      secondaryBpmnProcesses = {
          @BpmnProcess(bpmnProcessId = CALLED_PROCESS), @BpmnProcess(bpmnProcessId = RENAMED_PROCESS)
      })
  public static class OrderWorkflowService {

    @WorkflowTask(taskDefinition = "orderTask")
    public void orderTask(
        final OrderAggregate aggregate) {

      aggregate.setStatus("ordered");

    }

    @WorkflowTask(taskDefinition = "awaitShipment")
    public void awaitShipment(
        final OrderAggregate aggregate,
        @TaskId final String taskId) {

      aggregate.setStatus("awaiting-shipment");

    }

    @WorkflowEnded
    public void orderEnded(
        final OrderAggregate aggregate,
        final WorkflowEnd end) {

      ENDS_REPORTED.add("%s|%s|%s".formatted(aggregate.getId(), end.kind(), end.endEventId()));

    }

  }

  @Configuration
  static class ScenarioConfiguration {

    static final Map<String, OrderAggregate> AGGREGATES = new ConcurrentHashMap<>();

    /**
     * The model of the file: two executable processes, one task each. Nothing arrives for
     * the renamed id.
     */
    @Bean
    DummyTaskWiringSource orderingAndShippingWiringSource() {

      return new DummyTaskWiringSource() {

        @Override
        public List<String> executableProcessesOf(
            final String adapterId,
            final String workflowModuleId,
            final String filename) {

          return List.of(PRIMARY_PROCESS, CALLED_PROCESS);

        }

        @Override
        public List<BpmnTaskSpec> tasksOf(
            final String adapterId,
            final String workflowModuleId,
            final String bpmnProcessId) {

          return switch (bpmnProcessId) {
            case PRIMARY_PROCESS -> List.of(new BpmnTaskSpec("Activity_Order", "orderTask"));
            case CALLED_PROCESS -> List.of(new BpmnTaskSpec("Activity_AwaitShipment", "awaitShipment"));
            default -> List.of();
          };

        }

      };

    }

    @Bean
    AggregatePersistenceAware<OrderAggregate> orderPersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<OrderAggregate> getAggregateClass() {
          return OrderAggregate.class;
        }

        @Override
        public OrderAggregate save(
            final OrderAggregate aggregate) {
          AGGREGATES.put(aggregate.getId(), aggregate);
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final OrderAggregate aggregate) {
          return aggregate.getId();
        }

        @Override
        public Class<?> getAggregateIdType() {
          return String.class;
        }

        @Override
        public String getAggregateIdName() {
          return "id";
        }

        @Override
        public OrderAggregate loadById(
            final Object aggregateId) {
          return AGGREGATES.get(aggregateId);
        }

      };

    }

    @Bean
    DataSource calledProcessDataSource() {

      return new EmbeddedDatabaseBuilder()
          .setType(EmbeddedDatabaseType.H2)
          .generateUniqueName(true)
          .build();

    }

    @Bean
    PlatformTransactionManager transactionManager(
        final DataSource calledProcessDataSource) {

      return new DataSourceTransactionManager(calledProcessDataSource);

    }

  }

  private static final String APPLICATION_YAML = """
      vanillabp:
        prioritized-adapters:
          - test
        adapters:
          test:
            type: dummy
        delivery:
          release-on-workflow-end: true
        workflow-modules:
          test-module:
            adapters:
              test:
                resources-location: classpath*:test-module/processes/secondary
            workflows:
              Ordering:
                allow-full-sync-with-bpms: true
      """;

  private SpringBootTestApplication buildTestApp() throws IOException {

    return SpringBootTestApplication
        .builder()
        .addResource("META-INF/workflow-module")
        .addResource("application.yaml", APPLICATION_YAML)
        .addResource("test-module/processes/secondary/OrderingAndShipping.bpmn")
        .hideResource("META-INF/workflow-module")
        .hideResource("application.yaml")
        .build();

  }

  private ConfigurableApplicationContext runTestApplication(
      final SpringBootTestApplication testApp) {

    return testApp
        .applicationBuilder(
            DummyAdapterConfiguration.class,
            DummyAdapterProcessServiceConfiguration.class,
            WorkflowModuleAutoConfiguration.class,
            SpringBootMigrationAdapterAutoConfiguration.class,
            TestPersistenceConfiguration.class,
            TestPhaseTwoOutboxConfiguration.class,
            WorkflowModuleConfiguration.class,
            ScenarioConfiguration.class,
            OrderWorkflowService.class,
            // after the data source it is conditional on: listed as a plain source, so
            // its conditions see the bean definitions registered before it
            JdbcTaskDeliveryLogAutoConfiguration.class,
            DeploymentTest.TestConfig.class)
        .run();

  }

  private static DummyDeploymentService bpms(
      final ConfigurableApplicationContext context) {

    return context.getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);

  }

  private static void storeAggregate() {

    final var aggregate = new OrderAggregate();
    aggregate.setId(AGGREGATE);
    aggregate.setStatus("new");
    ScenarioConfiguration.AGGREGATES.put(AGGREGATE, aggregate);

  }

  private static void reset() {

    ScenarioConfiguration.AGGREGATES.clear();
    TestPhaseTwoOutboxConfiguration.clear();
    ENDS_REPORTED.clear();

  }

  private static TaskInvocationContext deliveryOfTheCalledProcess(
      final String taskId) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getTaskDefinition() {
        return "awaitShipment";
      }

      @Override
      public String getWorkflowAggregateId() {
        return AGGREGATE;
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

    };

  }

  private static WorkflowEndedContext ended(
      final WorkflowEnd.Kind kind,
      final String endEventId) {

    return new WorkflowEndedContext() {

      @Override
      public String getWorkflowAggregateId() {
        return AGGREGATE;
      }

      @Override
      public WorkflowEnd.Kind getKind() {
        return kind;
      }

      @Override
      public Instant getEndTime() {
        return Instant.now();
      }

      @Override
      public String getEndEventId() {
        return endEventId;
      }

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

    };

  }

  private static BpmsInitiatedStartContext startOfAnInstanceNamed(
      final String businessKey) {

    return new BpmsInitiatedStartContext() {

      @Override
      public String getStartEventId() {
        return "StartEvent_Shipping";
      }

      @Override
      public BpmsStartTrigger.Kind getKind() {
        return BpmsStartTrigger.Kind.NONE;
      }

      @Override
      public String getBusinessKey() {
        return businessKey;
      }

      @Override
      public String getNativeInstanceId() {
        return "instance-of-shipping";
      }

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

    };

  }

  private static int rowsUnder(
      final ConfigurableApplicationContext context,
      final String bpmnProcessId) {

    return new JdbcTemplate(context.getBean(DataSource.class))
        .queryForObject(
            "SELECT COUNT(*) FROM %s WHERE BPMN_PROCESS_ID = ? AND AGGREGATE_ID = ?"
                .formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
            Integer.class,
            bpmnProcessId,
            AGGREGATE);

  }

  @Test
  @DisplayName("Only the process at the top and the renamed id get a start and an end listener")
  public void aCalledProcessGetsNoListener() throws IOException {

    reset();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      Assertions.assertEquals(
          List.of(PRIMARY_PROCESS),
          bpms(context).getProcessesWithStartListener(),
          "the start of the called process is a step of the workflow which called it");
      Assertions.assertEquals(
          List.of(PRIMARY_PROCESS),
          bpms(context).getProcessesWithEndListener(),
          "the end of the called process is a step of the workflow which called it");

    }

  }

  @Test
  @DisplayName("The end of a called process is not reported, the end of the workflow is reported once")
  public void theWorkflowEndsOnce() throws IOException {

    reset();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      storeAggregate();
      bpms(context).notifyWorkflowEnded(MODULE, CALLED_PROCESS, ended(WorkflowEnd.Kind.CANCELED, null));
      Assertions.assertEquals(
          List.of(),
          ENDS_REPORTED,
          "a called process which was canceled by a boundary event did not end the workflow");

      bpms(context)
          .notifyWorkflowEnded(MODULE, PRIMARY_PROCESS, ended(WorkflowEnd.Kind.COMPLETED, "EndEvent_Ordering"));
      Assertions.assertEquals(List.of(AGGREGATE
          + "|COMPLETED|EndEvent_Ordering"), ENDS_REPORTED);

    }

  }

  @Test
  @DisplayName("A workflow of the id a renamed process left behind still ends for the application")
  public void aRenamedProcessKeepsItsEnd() throws IOException {

    reset();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      storeAggregate();
      bpms(context).notifyWorkflowEnded(MODULE, RENAMED_PROCESS, ended(WorkflowEnd.Kind.COMPLETED, "EndEvent_Old"));

      Assertions.assertEquals(List.of(AGGREGATE
          + "|COMPLETED|EndEvent_Old"), ENDS_REPORTED);

    }

  }

  @Test
  @DisplayName("The records of a called process are released when the workflow at the top ends")
  public void theRecordsOfTheCalledProcessGoWithTheWorkflow() throws IOException {

    reset();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      storeAggregate();
      bpms(context).invokeTask(MODULE, CALLED_PROCESS, deliveryOfTheCalledProcess("shipment-1"));
      Assertions.assertEquals(1, rowsUnder(context, CALLED_PROCESS));

      bpms(context).notifyWorkflowEnded(MODULE, CALLED_PROCESS, ended(WorkflowEnd.Kind.COMPLETED, null));
      Assertions.assertEquals(
          1,
          rowsUnder(context, CALLED_PROCESS),
          "the workflow goes on in the process which called the ended one");

      bpms(context)
          .notifyWorkflowEnded(MODULE, PRIMARY_PROCESS, ended(WorkflowEnd.Kind.COMPLETED, "EndEvent_Ordering"));
      Assertions.assertEquals(0, rowsUnder(context, CALLED_PROCESS));

    }

  }

  @Test
  @DisplayName("The start of a called process builds no aggregate and writes no start row")
  public void theStartOfACalledProcessIsNoStart() throws IOException {

    reset();

    try (var testApp = buildTestApp(); var context = runTestApplication(testApp)) {

      storeAggregate();
      final var result = bpms(context)
          .startWorkflowByBpms(MODULE, CALLED_PROCESS, startOfAnInstanceNamed(AGGREGATE));

      Assertions.assertEquals(AGGREGATE, result.workflowAggregateId());
      Assertions.assertFalse(result.created());
      Assertions.assertEquals(0, rowsUnder(context, CALLED_PROCESS), "no start row under the called process");
      Assertions.assertEquals(1, ScenarioConfiguration.AGGREGATES.size(), "no aggregate was built");

      final var refused = Assertions
          .assertThrows(
              IllegalStateException.class,
              () -> bpms(context).startWorkflowByBpms(MODULE, CALLED_PROCESS, startOfAnInstanceNamed(null)));
      Assertions.assertTrue(
          refused.getMessage().contains(
              "Let the call activity hand over the id of the caller's workflow aggregate in the process variable 'id'"),
          refused.getMessage());

    }

  }

}
