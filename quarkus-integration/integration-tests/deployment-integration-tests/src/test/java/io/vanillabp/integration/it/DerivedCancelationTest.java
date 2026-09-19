package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskExistence;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.runtime.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.cancelation.CancelAggregate;
import io.vanillabp.integration.test.cancelation.CancelAggregatePersistence;
import io.vanillabp.integration.test.cancelation.CancelAwarenessSource;
import io.vanillabp.integration.test.cancelation.CancelProcessWiringSource;
import io.vanillabp.integration.test.cancelation.CancelWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowEnd;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Acceptance test on Quarkus: an adapter reports the end of a workflow and names it, and
 * every task the core still believes is open in that workflow reaches the application as
 * canceled before <code>&#64;WorkflowEnded</code> runs.
 * <p>
 * The cases which must NOT derive are here too, because they are what keeps this
 * additive: an end which names no workflow, and a record which belongs to another
 * adapter.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DerivedCancelationTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "CancelProcess";

  private static final String ADAPTER = "demo1";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("derived-cancelation/application.yaml", "application.yaml")
          .addClass(CancelAggregate.class)
          .addClass(CancelAggregatePersistence.class)
          .addClass(CancelWorkflowService.class)
          .addClass(CancelProcessWiringSource.class)
          .addClass(CancelAwarenessSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/CancelProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  CancelAggregatePersistence persistence;

  @Inject
  DataSource dataSource;

  @Inject
  JdbcTaskDeliveryLog deliveryLog;

  @Inject
  UserTransaction userTransaction;

  @Inject
  CancelAwarenessSource awarenessSource;

  @Inject
  io.vanillabp.integration.extension.spi.election.WorkflowElection election;

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

  private int openRecordCount(
      final String aggregateId) throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection
        .prepareStatement(
            """
                SELECT COUNT(*) FROM %s WHERE AGGREGATE_ID = ? AND OUTCOME = 'COMPLETION_PENDING' \
                AND TASK_CLOSED_AT IS NULL"""
                .formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME))) {
      statement.setString(1, aggregateId);
      try (var resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getInt(1);
      }
    }

  }

  @Test
  @DisplayName("Every task open in an ended workflow is reported as canceled, and then the end is")
  public void theEndOfAWorkflowCancelsWhatItWasWaitingFor() throws SQLException {

    CancelWorkflowService.CANCELATIONS_WHICH_FAIL.set(0);
    final var dummyAdapter = dummyAdapter();

    persistence.store("4711");
    assertEquals(
        WorkflowTaskOutcome.Kind.COMPLETION_PENDING,
        dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitSignature", "4711", "task-1")).kind());
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitApproval", "4711", "task-2"));
    assertEquals(2, openRecordCount("4711"));

    dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4711", "workflow-of-4711"));

    assertEquals(
        "task-1,task-2,ended",
        persistence.get("4711").getWhatArrived(),
        "both tasks reach the application as canceled, oldest first, and the end runs after them");
    assertEquals(0, openRecordCount("4711"), "and both records are closed, so nothing derives them again");

    // the same notification once more, which is what at-least-once means: the records
    // are closed, so nothing is reported again
    dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4711", "workflow-of-4711"));
    assertEquals(
        "task-1,task-2,ended,ended",
        persistence.get("4711").getWhatArrived(),
        "the end is reported again, the cancellations are not");

  }

  @Test
  @DisplayName("An end which names no workflow derives nothing, and a record of another adapter is left alone")
  public void whatMustNotBeDerived() throws Exception {

    CancelWorkflowService.CANCELATIONS_WHICH_FAIL.set(0);
    final var dummyAdapter = dummyAdapter();

    // (a) an adapter which names no workflow keeps behaving as it did
    persistence.store("4712");
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitSignature", "4712", "task-3"));
    dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4712", null));
    assertEquals(
        "ended",
        persistence.get("4712").getWhatArrived(),
        "nothing is derived where the notification names no workflow");
    assertEquals(1, openRecordCount("4712"), "so the record stays open");

    // (b) a record which another BPMS wrote belongs to that BPMS
    persistence.store("4713");
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitSignature", "4713", "task-4"));
    userTransaction.begin();
    deliveryLog
        .record(
            new TaskDelivery("another-bpms|delivery", "another-bpms", MODULE, PROCESS, "4713", "workflow-of-4713", "awaitApproval", "Activity_Approval", "task-5", "COMPLETION_PENDING", null, null, Instant
                .now(), null));
    userTransaction.commit();
    assertEquals(2, openRecordCount("4713"));

    dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4713", "workflow-of-4713"));

    assertEquals(
        "task-4,ended",
        persistence.get("4713").getWhatArrived(),
        "only the task this adapter delivered is reported");
    assertEquals(
        1,
        openRecordCount("4713"),
        "the record of the other BPMS stays open - this end says nothing about it");

  }

  /**
   * The other half of the same mechanism: nothing ended, the BPMS simply does not have a
   * task any more, and a wake-up of that workflow is what finds out.
   */
  @Test
  @DisplayName("A wake-up reports the other tasks the BPMS does not have, and leaves the rest alone")
  public void aWakeUpProbesWhatItStillBelievesIsOpen() throws SQLException {

    CancelWorkflowService.CANCELATIONS_WHICH_FAIL.set(0);
    final var dummyAdapter = dummyAdapter();

    persistence.store("4715");
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitSignature", "4715", "task-7"));
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitApproval", "4715", "task-8"));
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitDelivery", "4715", "task-9"));
    assertEquals(3, openRecordCount("4715"));

    // task-9 is the job being worked on. Of the other two the BPMS has one and not the
    // other
    final var probed = new java.util.ArrayList<String>();
    dummyAdapter
        .reportTasksTheBpmsNoLongerHas(
            MODULE,
            PROCESS,
            delivery("awaitDelivery", "4715", "task-9"),
            (
                workflowId,
                taskId) -> {
              probed.add(taskId);
              return "task-7".equals(taskId)
                  ? TaskExistence.GONE
                  : TaskExistence.STILL_THERE;
            });

    assertEquals(
        List.of("task-7", "task-8"),
        probed,
        "the task of the wake-up itself is never probed, and the others come oldest first");
    assertEquals(
        "task-7",
        persistence.get("4715").getWhatArrived(),
        "only the task the BPMS does not have reaches the application");
    assertEquals(2, openRecordCount("4715"), "its record is closed, the two others stay open");

  }

  /**
   * What an extension asks for, and what the election hands the adapter on the way: both
   * come out of the same row the delivery record wrote.
   */
  @Test
  @DisplayName("The election carries the workflow id, and an extension gets it back")
  public void theElectionCarriesTheWorkflowId() {

    CancelWorkflowService.CANCELATIONS_WHICH_FAIL.set(0);
    awarenessSource.forgetWhatWasAsked();
    final var dummyAdapter = dummyAdapter();

    // a task was delivered for this workflow, so its record knows what the BPMS calls it
    persistence.store("4716");
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitSignature", "4716", "task-10"));

    final var located = election.locationOfWorkflow(MODULE, PROCESS, "4716");
    assertEquals(ADAPTER, located.adapterId());
    assertEquals(
        "workflow-of-4716",
        located.workflowId(),
        "the id stands in the same row as the adapter id, so both come back");
    assertEquals(
        List.of("workflow-of-4716"),
        awarenessSource.getWorkflowIdsTheElectionPassed(),
        "and the adapter saw it while it was asked");

    // the old call still answers its string
    assertEquals(ADAPTER, election.adapterIdOfWorkflow(MODULE, PROCESS, "4716"));

    // a workflow nobody delivered anything for: the adapter id and no workflow id, which
    // is a regular answer
    awarenessSource.forgetWhatWasAsked();
    persistence.store("4717");
    final var withoutAnId = election.locationOfWorkflow(MODULE, PROCESS, "4717");
    assertEquals(ADAPTER, withoutAnId.adapterId());
    assertNull(withoutAnId.workflowId());
    assertEquals(
        List.of("null"),
        awarenessSource.getWorkflowIdsTheElectionPassed(),
        "the adapter is asked without an id and answers as it always did");

  }

  @Test
  @DisplayName("A handler which throws leaves the record open, and the next end reports the task again")
  public void aHandlerWhichThrowsLeavesTheRecordOpen() throws SQLException {

    CancelWorkflowService.CANCELATIONS_WHICH_FAIL.set(1);
    final var dummyAdapter = dummyAdapter();

    persistence.store("4714");
    dummyAdapter.invokeTask(MODULE, PROCESS, delivery("awaitSignature", "4714", "task-6"));

    dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4714", "workflow-of-4714"));

    assertEquals(
        "ended",
        persistence.get("4714").getWhatArrived(),
        "the handler threw, so nothing of that delivery was written - the end still ran");
    assertEquals(1, openRecordCount("4714"), "the claim went back with the transaction, so the record is open again");

    dummyAdapter.notifyWorkflowEnded(MODULE, PROCESS, workflowEnded("4714", "workflow-of-4714"));

    assertEquals(
        "ended,task-6,ended",
        persistence.get("4714").getWhatArrived(),
        "the second run derives the task again, which is what at-least-once means");
    assertEquals(0, openRecordCount("4714"));

  }

}
