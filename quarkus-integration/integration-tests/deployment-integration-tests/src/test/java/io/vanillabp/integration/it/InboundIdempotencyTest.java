package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.adapter.dummy.runtime.DummyDeploymentService;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.test.delivery.DeliveryAggregate;
import io.vanillabp.integration.test.delivery.DeliveryAggregatePersistence;
import io.vanillabp.integration.test.delivery.DeliveryProcessWiringSource;
import io.vanillabp.integration.test.delivery.DeliveryWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test of the inbound idempotency (story 51) on Quarkus, with the default
 * JDBC-based delivery log doing the remembering: the dummy adapter delivers a task TWICE
 * under the same delivery identity - as a BPMS which never learned the result does - and
 * the <code>&#64;WorkflowTask</code> method has to run once while both deliveries are
 * answered with the same outcome. Also pinned: a delivery whose handler threw leaves no
 * record (its retry runs the handler again) and the task-level switch turns the feature
 * off.
 */
@ExtendWith(SuppressOutputExtension.class)
public class InboundIdempotencyTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "DeliveryProcess";

  private static final String ADAPTER = "demo1";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("inbound-idempotency/application.yaml", "application.yaml")
          .addClass(DeliveryAggregate.class)
          .addClass(DeliveryAggregatePersistence.class)
          .addClass(DeliveryWorkflowService.class)
          .addClass(DeliveryProcessWiringSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/DeliveryProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  DeliveryAggregatePersistence persistence;

  @Inject
  DataSource dataSource;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

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

  /**
   * One delivery of a task, as an adapter of a remote BPMS builds it: the delivery ID is
   * what stays the same when the BPMS repeats it.
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

  private int recordCount(
      final String aggregateId) throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
        "SELECT COUNT(*) FROM %s WHERE AGGREGATE_ID = ?".formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME))) {
      statement.setString(1, aggregateId);
      try (var resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }

  }

  @Test
  @DisplayName("A repeated delivery runs the handler once and reports the recorded outcome")
  public void repeatedDeliveriesRunTheHandlerOnce() throws SQLException {

    final var dummyAdapter = dummyAdapter();

    persistence.store("4711");
    final var first = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-1"));
    final var second = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-1"));

    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, first.kind());
    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, second.kind());
    assertEquals(1, persistence.get("4711").getInvocations());
    assertEquals(1, recordCount("4711"));

    // the next task instance of the same workflow is another delivery
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("processTask", "4711", "job-2"));
    assertEquals(2, persistence.get("4711").getInvocations());
    assertEquals(2, recordCount("4711"));

  }

  @Test
  @DisplayName("A repeated delivery of a BPMN error reports code and name again")
  public void repeatedDeliveryReportsTheRecordedBpmnError() {

    final var dummyAdapter = dummyAdapter();

    persistence.store("4712");
    final var error = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("raiseBpmnError", "4712", "job-3"));
    final var errorAgain = dummyAdapter.invokeTask(MODULE, PROCESS, delivery("raiseBpmnError", "4712", "job-3"));

    assertEquals(WorkflowTaskOutcome.Kind.BPMN_ERROR, errorAgain.kind());
    assertEquals(error.errorCode(), errorAgain.errorCode());
    assertEquals("PAYMENT_FAILED", errorAgain.errorCode());
    assertEquals("PaymentFailed", errorAgain.errorName());
    assertEquals(1, persistence.get("4712").getInvocations());

  }

  @Test
  @DisplayName("A rolled-back delivery leaves no record, so its retry runs the handler")
  public void aRolledBackDeliveryLeavesNoRecord() throws SQLException {

    final var dummyAdapter = dummyAdapter();

    persistence.store("4713");
    assertThrows(
        IllegalStateException.class,
        () -> dummyAdapter.invokeTask(MODULE, PROCESS, delivery("failTask", "4713", "job-4")));
    assertEquals(0, recordCount("4713"));

    assertThrows(
        IllegalStateException.class,
        () -> dummyAdapter.invokeTask(MODULE, PROCESS, delivery("failTask", "4713", "job-4")));
    assertEquals(0, recordCount("4713"));

  }

  @Test
  @DisplayName("Switched off for a task, nothing is remembered")
  public void aTaskMayOptOut() throws SQLException {

    final var dummyAdapter = dummyAdapter();

    persistence.store("4714");
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("undeduplicatedTask", "4714", "job-5"));
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("undeduplicatedTask", "4714", "job-5"));

    assertEquals(2, persistence.get("4714").getInvocations());
    assertEquals(0, recordCount("4714"));

  }

}
