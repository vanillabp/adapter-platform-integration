package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.ExtensionWorkflowElection;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.extension.spi.election.OpenUserTask;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.DeliveryRecordKind;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An extension reads the open user tasks of an aggregate from the delivery log, without an
 * election and without asking a BPMS. A task of a called process names the instance it runs in,
 * and the workflow of the aggregate comes from the row written when the workflow started.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OpenUserTasksOfAnAggregateTest {

  private static final String MODULE = "open-tasks-module";

  private static final String PROCESS = "MainProcess";

  private static final String CALLED_PROCESS = "CalledProcess";

  private static final String ADAPTER = "c8";

  private static final String WORKFLOW = "2251799813685249";

  private static final String SUB_WORKFLOW = "2251799813685300";

  private static final String AGGREGATE = "4711";

  /** A delivery log in memory, which keeps what it is told and answers by key and by aggregate. */
  static class DeliveryLogInMemory implements TaskDeliveryLog {

    final Map<String, TaskDelivery> records = new HashMap<>();

    boolean broken;

    @Override
    public Optional<TaskDelivery> recordedDelivery(
        final String deliveryKey) {

      return Optional.ofNullable(records.get(deliveryKey));

    }

    @Override
    public boolean record(
        final TaskDelivery delivery) {

      return records.putIfAbsent(delivery.deliveryKey(), delivery) == null;

    }

    @Override
    public List<TaskDelivery> openTasksOfAggregate(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {

      if (broken) {
        throw new IllegalStateException("the database went away");
      }
      return records
          .values()
          .stream()
          .filter(record -> DeliveryRecordKind.of(record.recordKind()) == DeliveryRecordKind.TASK_DELIVERY)
          .filter(record -> bpmnProcessId.equals(record.bpmnProcessId()))
          .filter(record -> workflowAggregateId.equals(record.workflowAggregateId()))
          .filter(record -> "COMPLETION_PENDING".equals(record.outcome()))
          .sorted(java.util.Comparator.comparing(TaskDelivery::recordedAt))
          .toList();

    }

  }

  /** An adapter which is never asked: every answer here comes from the delivery log. */
  static class AnAdapterNobodyAsks implements MigratableProcessService<Object> {

    @Override
    public String getAdapterId() {

      return ADAPTER;

    }

    @Override
    public Map<PhaseOperation, PhaseOperationHandler<Object>> phaseOperations() {

      final var operations = new HashMap<PhaseOperation, PhaseOperationHandler<Object>>();
      PhaseOperation.CORE_OPERATIONS
          .forEach(operation -> operations.put(operation, PhaseOperationHandler.of(request -> {
          }, request -> {
          })));
      return operations;

    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {

      throw new AssertionError("reading the open user tasks must not ask a BPMS");

    }

    @Override
    public WorkflowAwareness awarenessOfUserTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {

      throw new AssertionError("reading the open user tasks must not ask a BPMS");

    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {

      throw new AssertionError("reading the open user tasks must not ask a BPMS");

    }

  }

  /** The aggregates are never read: every answer here comes from the delivery log. */
  static class NoAggregatesAreRead implements AggregatePersistenceAware<Object> {

    @Override
    public Class<Object> getAggregateClass() {
      return Object.class;
    }

    @Override
    public String getAggregateIdName() {
      return "id";
    }

    @Override
    public Class<?> getAggregateIdType() {
      return String.class;
    }

    @Override
    public Object getAggregateId(
        final Object aggregate) {
      throw new AssertionError("reading the open user tasks must not read an aggregate");
    }

    @Override
    public Object save(
        final Object aggregate) {
      throw new AssertionError("reading the open user tasks must not save an aggregate");
    }

    @Override
    public Object loadById(
        final Object aggregateId) {
      throw new AssertionError("reading the open user tasks must not read an aggregate");
    }

  }

  private final DeliveryLogInMemory deliveryLog = new DeliveryLogInMemory();

  private WorkflowElection election;

  @BeforeEach
  public void givenOneWorkflowServiceWithACalledProcess() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    final var processService = MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Object.class)
        .properties(properties)
        .aggregatePersistence(new NoAggregatesAreRead())
        .phaseTwoOutboxResolver(new PhaseTwoOutboxResolver() {

          @Override
          public PhaseTwoOutbox resolveFor(
              final Class<?> workflowAggregateClass) {

            return call -> {
              throw new AssertionError("reading the open user tasks must not schedule anything");
            };

          }

          @Override
          public String remediesDescription() {

            return "";

          }

          @Override
          public java.util.Collection<PhaseTwoOutbox> allStores() {

            return List.of();

          }

        })
        .processServices(List.of(new AnAdapterNobodyAsks()))
        .taskDeliveryLogResolver(new TaskDeliveryLogResolver() {

          @Override
          public TaskDeliveryLog resolveFor(
              final Class<?> workflowAggregateClass) {

            return deliveryLog;

          }

          @Override
          public String remediesDescription() {

            return "";

          }

        })
        .build();
    processService.setServedBpmnProcessIds(List.of(PROCESS, CALLED_PROCESS));
    final var router = new PhaseTwoRouter();
    router.register(processService);
    election = new ExtensionWorkflowElection(router);

  }

  private void givenTheWorkflowStarted() {

    deliveryLog
        .record(TaskDelivery.workflowStart(ADAPTER, MODULE, PROCESS, AGGREGATE, WORKFLOW, "3", Instant.now()));

  }

  private void givenAnOpenTask(
      final String key,
      final String bpmnProcessId,
      final String workflowId,
      final String taskKind,
      final String outcome,
      final Instant deliveredAt) {

    final var taskDefinition = "def-"
        + key;
    final var element = "element-"
        + key;
    final var taskId = "task-"
        + key;
    final var version = bpmnProcessId.equals(PROCESS) ? "3" : "7";
    final var kind = DeliveryRecordKind.TASK_DELIVERY.name();
    deliveryLog
        .record(
            new TaskDelivery(
                key, ADAPTER, MODULE, bpmnProcessId, AGGREGATE, workflowId, taskDefinition, element, taskId, outcome, null, null, deliveredAt, null, taskKind, kind, version));

  }

  @Test
  @DisplayName("Open user tasks of the main and a called process are answered, oldest first, with the workflow of the aggregate")
  public void openUserTasksAreAnswered() {

    final var now = Instant.now();
    givenTheWorkflowStarted();
    givenAnOpenTask("review", PROCESS, WORKFLOW, "USER_TASK", "COMPLETION_PENDING", now.minusSeconds(20));
    givenAnOpenTask("approve", CALLED_PROCESS, SUB_WORKFLOW, "USER_TASK", "COMPLETION_PENDING", now.minusSeconds(30));

    assertEquals(
        List
            .of(
                new OpenUserTask(
                    ADAPTER, WORKFLOW, SUB_WORKFLOW, CALLED_PROCESS, "task-approve", "def-approve", "element-approve", "7"),
                new OpenUserTask(
                    ADAPTER, WORKFLOW, WORKFLOW, PROCESS, "task-review", "def-review", "element-review", "3")),
        election.openUserTasksOf(MODULE, PROCESS, AGGREGATE));

  }

  @Test
  @DisplayName("Service tasks, rows without a kind and completed tasks are not in the answer")
  public void onlyOpenUserTasksAreAnswered() {

    final var now = Instant.now();
    givenTheWorkflowStarted();
    givenAnOpenTask("service", PROCESS, WORKFLOW, "TASK", "COMPLETION_PENDING", now);
    givenAnOpenTask("unknown-kind", PROCESS, WORKFLOW, null, "COMPLETION_PENDING", now);
    givenAnOpenTask("done", PROCESS, WORKFLOW, "USER_TASK", "COMPLETED", now);
    givenAnOpenTask("open", PROCESS, WORKFLOW, "USER_TASK", "COMPLETION_PENDING", now);

    assertEquals(
        List.of("task-open"),
        election.openUserTasksOf(MODULE, PROCESS, AGGREGATE).stream().map(OpenUserTask::userTaskId).toList());

  }

  @Test
  @DisplayName("Without a start row the workflow of the aggregate stays empty, the instance of the task does not")
  public void withoutAStartRowTheWorkflowIsEmpty() {

    givenAnOpenTask("approve", CALLED_PROCESS, SUB_WORKFLOW, "USER_TASK", "COMPLETION_PENDING", Instant.now());

    final var answer = election.openUserTasksOf(MODULE, PROCESS, AGGREGATE);

    assertEquals(1, answer.size());
    assertEquals(null, answer.get(0).workflowId());
    assertEquals(SUB_WORKFLOW, answer.get(0).subWorkflowId());

  }

  @Test
  @DisplayName("Without a start row the workflow of the aggregate is never the instance of a called process")
  public void withoutAStartRowACalledProcessNamesNoWorkflow() {

    givenAnOpenTask("approve", CALLED_PROCESS, SUB_WORKFLOW, "USER_TASK", "COMPLETION_PENDING", Instant.now());

    assertTrue(
        election.workflowIdOf(MODULE, PROCESS, AGGREGATE).isEmpty(),
        "the open task of the called process names its own instance, which is not the workflow");
    assertTrue(election.workflowStartOf(MODULE, PROCESS, AGGREGATE).isEmpty());

    givenAnOpenTask("review", PROCESS, WORKFLOW, "USER_TASK", "COMPLETION_PENDING", Instant.now());

    assertEquals(
        java.util.Optional.of(WORKFLOW),
        election.workflowIdOf(MODULE, PROCESS, AGGREGATE),
        "an open task of the process itself still names the workflow where the start row is missing");
    assertEquals(WORKFLOW, election.workflowStartOf(MODULE, PROCESS, AGGREGATE).orElseThrow().workflowId());

  }

  @Test
  @DisplayName("Nothing known, a process this application does not serve, and a log which cannot be read answer an empty list")
  public void nothingKnownIsAnEmptyList() {

    givenTheWorkflowStarted();
    givenAnOpenTask("review", PROCESS, WORKFLOW, "USER_TASK", "COMPLETION_PENDING", Instant.now());

    assertTrue(election.openUserTasksOf(MODULE, PROCESS, "4799").isEmpty());
    assertTrue(election.openUserTasksOf(MODULE, "AnotherProcess", AGGREGATE).isEmpty());
    deliveryLog.broken = true;
    assertTrue(election.openUserTasksOf(MODULE, PROCESS, AGGREGATE).isEmpty());

  }

  @Test
  @DisplayName("An election written before this existed answers an empty list")
  public void anOlderElectionAnswersNothing() {

    final WorkflowElection olderElection = (
        workflowModuleId,
        bpmnProcessId,
        workflowAggregateId) -> ADAPTER;

    assertTrue(olderElection.openUserTasksOf(MODULE, PROCESS, AGGREGATE).isEmpty());

  }

}
