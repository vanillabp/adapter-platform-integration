package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.test.secondary.CalledProcessWorkflowService;
import io.vanillabp.integration.test.secondary.OrderAggregate;
import io.vanillabp.integration.test.secondary.OrderAggregatePersistence;
import io.vanillabp.integration.test.secondary.OrderAwarenessSource;
import io.vanillabp.integration.test.secondary.OrderOutbox;
import io.vanillabp.integration.test.secondary.OrderOutboxAware;
import io.vanillabp.integration.test.secondary.OrderingAndShippingWiringSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowEnd;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * A called process is no workflow of its own, on Quarkus: its start and its end do not reach
 * the application, and the workflow at the top ends once.
 * <p>
 * The workflow service serves {@code Ordering} and declares {@code Shipping}, which
 * {@code Ordering} calls, as a secondary process. It declares {@code Dispatching} as well, the
 * id a renamed process left behind, and no model arrives for it. The workflows the BPMS still
 * holds under that id were started under it, so they keep their end.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ACalledProcessIsNoWorkflowTest {

  private static final String MODULE = "called-module";

  private static final String PRIMARY_PROCESS = "Ordering";

  private static final String CALLED_PROCESS = "Shipping";

  private static final String RENAMED_PROCESS = "Dispatching";

  private static final String ADAPTER = "demo1";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("called-process/application.yaml", "application.yaml")
          .addClass(OrderAggregate.class)
          .addClass(OrderAggregatePersistence.class)
          .addClass(CalledProcessWorkflowService.class)
          .addClass(OrderingAndShippingWiringSource.class)
          .addClass(OrderAwarenessSource.class)
          .addClass(OrderOutbox.class)
          .addClass(OrderOutboxAware.class)
          .addAsResource("bpmn/first.bpmn", "processes/called/Ordering.bpmn")
          .addAsResource("called-process/workflow-module", "META-INF/workflow-module"));

  @Inject
  OrderAggregatePersistence persistence;

  @Inject
  DataSource dataSource;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

  @BeforeEach
  void forgetTheEndsOfTheTestBefore() {

    CalledProcessWorkflowService.ENDS_REPORTED.clear();

  }

  private DummyDeploymentService dummyAdapter() {

    return deploymentServices
        .stream()
        .filter(java.util.Objects::nonNull)
        .flatMap(List::stream)
        .filter(java.util.Objects::nonNull)
        .filter(DummyDeploymentService.class::isInstance)
        .map(DummyDeploymentService.class::cast)
        .filter(service -> ADAPTER.equals(service.getAdapterId()))
        .findFirst()
        .orElseThrow();

  }

  private static TaskInvocationContext deliveryOfTheCalledProcess(
      final String workflowAggregateId,
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
        return workflowAggregateId;
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
      final String workflowAggregateId,
      final WorkflowEnd.Kind kind,
      final String endEventId) {

    return new WorkflowEndedContext() {

      @Override
      public String getWorkflowAggregateId() {
        return workflowAggregateId;
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
      public String getAdapterId() {
        return ADAPTER;
      }

    };

  }

  private int rowsUnder(
      final String bpmnProcessId,
      final String workflowAggregateId) throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection
        .prepareStatement(
            "SELECT COUNT(*) FROM %s WHERE BPMN_PROCESS_ID = ? AND AGGREGATE_ID = ?"
                .formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME))) {
      statement.setString(1, bpmnProcessId);
      statement.setString(2, workflowAggregateId);
      try (var resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }

  }

  @Test
  @DisplayName("Only the process at the top gets a start and an end listener")
  public void aCalledProcessGetsNoListener() {

    assertEquals(
        List.of(PRIMARY_PROCESS),
        dummyAdapter().getProcessesWithStartListener(),
        "the start of the called process is a step of the workflow which called it");
    assertEquals(
        List.of(PRIMARY_PROCESS),
        dummyAdapter().getProcessesWithEndListener(),
        "the end of the called process is a step of the workflow which called it");

  }

  @Test
  @DisplayName("The end of a called process is not reported, the end of the workflow is reported once")
  public void theWorkflowEndsOnce() {

    persistence.store("5001");

    dummyAdapter().notifyWorkflowEnded(MODULE, CALLED_PROCESS, ended("5001", WorkflowEnd.Kind.CANCELED, null));
    assertEquals(
        List.of(),
        CalledProcessWorkflowService.ENDS_REPORTED,
        "a called process which was canceled by a boundary event did not end the workflow");

    dummyAdapter()
        .notifyWorkflowEnded(MODULE, PRIMARY_PROCESS, ended("5001", WorkflowEnd.Kind.COMPLETED, "EndEvent_Ordering"));
    assertEquals(List.of("5001|COMPLETED|EndEvent_Ordering"), CalledProcessWorkflowService.ENDS_REPORTED);

  }

  @Test
  @DisplayName("A workflow of the id a renamed process left behind still ends for the application")
  public void aRenamedProcessKeepsItsEnd() {

    persistence.store("5002");

    dummyAdapter()
        .notifyWorkflowEnded(MODULE, RENAMED_PROCESS, ended("5002", WorkflowEnd.Kind.COMPLETED, "EndEvent_Old"));

    assertEquals(List.of("5002|COMPLETED|EndEvent_Old"), CalledProcessWorkflowService.ENDS_REPORTED);

  }

  @Test
  @DisplayName("The records of a called process are released when the workflow at the top ends")
  public void theRecordsOfTheCalledProcessGoWithTheWorkflow() throws Exception {

    persistence.store("5003");
    dummyAdapter().invokeTask(MODULE, CALLED_PROCESS, deliveryOfTheCalledProcess("5003", "shipment-5003"));
    assertEquals(1, rowsUnder(CALLED_PROCESS, "5003"));

    dummyAdapter().notifyWorkflowEnded(MODULE, CALLED_PROCESS, ended("5003", WorkflowEnd.Kind.COMPLETED, null));
    assertEquals(1, rowsUnder(CALLED_PROCESS, "5003"), "the workflow goes on in the process which called it");

    dummyAdapter()
        .notifyWorkflowEnded(MODULE, PRIMARY_PROCESS, ended("5003", WorkflowEnd.Kind.COMPLETED, "EndEvent_Ordering"));
    assertEquals(0, rowsUnder(CALLED_PROCESS, "5003"));

  }

  @Test
  @DisplayName("The start of a called process builds no aggregate and writes no start row")
  public void theStartOfACalledProcessIsNoStart() throws Exception {

    persistence.store("5004");

    final var result = dummyAdapter().startWorkflowByBpms(MODULE, CALLED_PROCESS, startOfAnInstanceNamed("5004"));

    assertEquals("5004", result.workflowAggregateId());
    assertFalse(result.created());
    assertEquals(0, rowsUnder(CALLED_PROCESS, "5004"), "no start row under the called process");

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> dummyAdapter().startWorkflowByBpms(MODULE, CALLED_PROCESS, startOfAnInstanceNamed(null)));
    assertTrue(
        refused.getMessage().contains("Let the call activity hand over the id of the caller's workflow aggregate"),
        refused.getMessage());

  }

}
