package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.WorkflowNotFoundException;

/**
 * An operation on a workflow, for a node which has no hint about that workflow in its election
 * cache: after a restart, on a second node, or after the cache forgot. The row written when the
 * workflow started is in the application's database and says which adapter started it, so an
 * adapter which does not report the workflow yet reads as "not visible yet" rather than as "nobody
 * knows this workflow". Without the row everything stays as it was.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AnOperationReadsTheStartRowTest {

  private static final String MODULE = "start-row-module";

  private static final String PROCESS = "StartRowProcess";

  private static final String ADAPTER = "c8";

  private static final String WORKFLOW = "2251799813685249";

  private static final String AGGREGATE = "4711";

  private static final String TASK = "2251799813685300";

  /**
   * An adapter whose read model is behind: it answers what the test sets, counts its phase two
   * per operation and writes down the workflow id it was handed.
   */
  static class AnAdapterWithALaggingReadModel implements MigratableProcessService<Object> {

    volatile WorkflowAwareness answer = WorkflowAwareness.UNKNOWN_TO_BPMS;

    final Map<PhaseOperation, AtomicInteger> phaseTwoRuns = new HashMap<>();

    final List<String> workflowIdsHanded = new CopyOnWriteArrayList<>();

    final List<String> workflowIdsInPhaseTwo = new CopyOnWriteArrayList<>();

    final List<Optional<TaskDelivery>> taskRowsInPhaseTwo = new CopyOnWriteArrayList<>();

    final AtomicInteger redispatchProbes = new AtomicInteger();

    final List<Optional<Instant>> planningMomentsHanded = new CopyOnWriteArrayList<>();

    AnAdapterWithALaggingReadModel() {

      PhaseOperation.CORE_OPERATIONS.forEach(operation -> phaseTwoRuns.put(operation, new AtomicInteger()));

    }

    @Override
    public String getAdapterId() {

      return ADAPTER;

    }

    @Override
    public Map<PhaseOperation, PhaseOperationHandler<Object>> phaseOperations() {

      final var operations = new HashMap<PhaseOperation, PhaseOperationHandler<Object>>();
      PhaseOperation.CORE_OPERATIONS
          .forEach(operation -> {
            final var runs = phaseTwoRuns.get(operation);
            operations
                .put(operation, PhaseOperationHandler.of(request -> {
                }, request -> {
                  runs.incrementAndGet();
                  if (operation != PhaseOperation.START_WORKFLOW) {
                    workflowIdsInPhaseTwo.add(String.valueOf(request.workflowId()));
                    taskRowsInPhaseTwo.add(Optional.ofNullable(request.taskRecord()));
                  }
                }));
          });
      return operations;

    }

    int phaseTwoRunsOf(
        final PhaseOperation operation) {

      return phaseTwoRuns.get(operation).get();

    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {

      return WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Override
    public WorkflowAwareness awarenessOfUserTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {

      return WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {

      return answer;

    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String workflowId) {

      workflowIdsHanded.add(String.valueOf(workflowId));
      return answer;

    }

    @Override
    public WorkflowAwareness awarenessOfWorkflowForRedispatch(
        final WorkflowScope scope,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {

      redispatchProbes.incrementAndGet();
      return answer;

    }

    @Override
    public WorkflowAwareness awarenessOfWorkflowForRedispatch(
        final WorkflowScope scope,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final Instant plannedAt) {

      planningMomentsHanded.add(Optional.ofNullable(plannedAt));
      return awarenessOfWorkflowForRedispatch(scope, aggregatePersistence, workflowAggregateId);

    }

  }

  /** A delivery log in memory which answers by key, which is all the start row needs. */
  static class DeliveryLogInMemory implements TaskDeliveryLog {

    final Map<String, TaskDelivery> records = new HashMap<>();

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
    public Optional<TaskDelivery> recordOfTask(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId,
        final String taskId) {

      return records
          .values()
          .stream()
          .filter(row -> taskId.equals(row.taskId()))
          .filter(row -> workflowAggregateId.equals(row.workflowAggregateId()))
          .findFirst();

    }

  }

  private final AnAdapterWithALaggingReadModel adapter = new AnAdapterWithALaggingReadModel();

  private final DeliveryLogInMemory deliveryLog = new DeliveryLogInMemory();

  private final List<PhaseTwoCall> scheduled = new ArrayList<>();

  private MigrationProcessService<Object> processService(
      final WorkflowAdapterCache cache) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Object.class)
        .properties(properties)
        .aggregatePersistence(new AggregatePersistenceAware<>() {

          @Override
          public Class<Object> getAggregateClass() {

            return Object.class;

          }

          @Override
          public Class<?> getAggregateIdType() {

            return String.class;

          }

          @Override
          public Object save(
              final Object workflowAggregate) {

            return workflowAggregate;

          }

          @Override
          public Object getAggregateId(
              final Object workflowAggregate) {

            return workflowAggregate;

          }

        })
        .processServices(List.of(adapter))
        .workflowAdapterCache(cache)
        .phaseTwoOutboxResolver(new PhaseTwoOutboxResolver() {

          @Override
          public PhaseTwoOutbox resolveFor(
              final Class<?> workflowAggregateClass) {

            return scheduled::add;

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

  }

  /** What a node which just restarted has: an empty cache. */
  private MigrationProcessService<Object> aNodeWithoutAHint() {

    return processService(new InMemoryWorkflowAdapterCache());

  }

  private void theWorkflowWasStartedBy(
      final String adapterId) {

    theWorkflowWasStartedAt(adapterId, Instant.now());

  }

  private void theWorkflowWasStartedAt(
      final String adapterId,
      final Instant startedAt) {

    deliveryLog
        .recordWorkflowStart(
            TaskDelivery.workflowStart(adapterId, MODULE, PROCESS, AGGREGATE, WORKFLOW, "1", startedAt));

  }

  private static Map<String, String> plannedAt(
      final Instant plannedAt) {

    return Map.of(PhaseTwoCall.ARG_PLANNED_AT, plannedAt.toString());

  }

  @Test
  @DisplayName("Phase one plans the operation where the start row names the adapter, although the BPMS does not report the workflow yet")
  public void phaseOnePlansTheOperation() {

    theWorkflowWasStartedBy(ADAPTER);
    final var processService = aNodeWithoutAHint();

    assertDoesNotThrow(() -> processService.correlateMessage(AGGREGATE, "PaymentReceived", null));
    assertDoesNotThrow(() -> processService.aggregateChanged(AGGREGATE, null));

    assertEquals(
        List.of(PhaseOperation.CORRELATE_MESSAGE.name(), PhaseOperation.AGGREGATE_CHANGED.name()),
        scheduled
            .stream()
            .map(PhaseTwoCall::operation)
            .toList());

  }

  @Test
  @DisplayName("Phase two repeats the entry where the start row names the adapter, and runs it once the BPMS caught up")
  public void phaseTwoRepeatsTheEntryInsteadOfConsumingIt() {

    theWorkflowWasStartedBy(ADAPTER);
    final var processService = aNodeWithoutAHint();

    assertThrows(
        RuntimeException.class,
        () -> processService.executePhaseTwo(PhaseOperation.AGGREGATE_CHANGED, AGGREGATE, null, Map.of(), false),
        "an entry which is thrown back is repeated by the store, one which returns is consumed");
    assertEquals(0, adapter.phaseTwoRunsOf(PhaseOperation.AGGREGATE_CHANGED));

    adapter.answer = WorkflowAwareness.ACTIVE;
    processService.executePhaseTwo(PhaseOperation.AGGREGATE_CHANGED, AGGREGATE, null, Map.of(), true);

    assertEquals(1, adapter.phaseTwoRunsOf(PhaseOperation.AGGREGATE_CHANGED));
    assertEquals(
        List.of(WORKFLOW),
        adapter.workflowIdsInPhaseTwo,
        "phase two is handed the workflow the adapter started, so it can address it by its id");

  }

  @Test
  @DisplayName("The probe is handed the workflow id of the start row, in both phases")
  public void theProbeGetsTheWorkflowIdOfTheRow() {

    theWorkflowWasStartedBy(ADAPTER);
    adapter.answer = WorkflowAwareness.ACTIVE;
    final var processService = processService(null);

    processService.correlateMessage(AGGREGATE, "PaymentReceived", null);
    processService
        .executePhaseTwo(
            PhaseOperation.CORRELATE_MESSAGE,
            AGGREGATE,
            null,
            Map.of(PhaseTwoCall.ARG_MESSAGE_NAME, "PaymentReceived"),
            false);

    assertEquals(List.of(WORKFLOW, WORKFLOW), adapter.workflowIdsHanded);
    assertEquals(1, adapter.phaseTwoRunsOf(PhaseOperation.CORRELATE_MESSAGE));

  }

  @Test
  @DisplayName("A start row which proved right is remembered, so the next election reads the cache")
  public void aRowWhichProvedRightFillsTheCache() {

    theWorkflowWasStartedBy(ADAPTER);
    adapter.answer = WorkflowAwareness.ACTIVE;
    final var cache = new InMemoryWorkflowAdapterCache();
    final var processService = processService(cache);

    processService.correlateMessage(AGGREGATE, "PaymentReceived", null);

    final var hint = cache.hintOf(MODULE, PROCESS, AGGREGATE).orElseThrow();
    assertEquals(ADAPTER, hint.adapterId());
    assertEquals(WORKFLOW, hint.workflowId());

  }

  @Test
  @DisplayName("Without a start row a workflow nobody reports still fails in phase one and is consumed in phase two")
  public void withoutARowNothingChanges() {

    final var processService = aNodeWithoutAHint();

    assertThrows(
        WorkflowNotFoundException.class,
        () -> processService.correlateMessage(AGGREGATE, "PaymentReceived", null));
    assertDoesNotThrow(
        () -> processService.executePhaseTwo(PhaseOperation.AGGREGATE_CHANGED, AGGREGATE, null, Map.of(), false),
        "the entry is consumed as stale");
    assertEquals(0, adapter.phaseTwoRunsOf(PhaseOperation.AGGREGATE_CHANGED));
    assertEquals(List.of("null", "null"), adapter.workflowIdsHanded, "and no id was there to hand on");

  }

  @Test
  @DisplayName("A start row naming an adapter the configuration dropped does not count")
  public void aRowOfADroppedAdapterDoesNotCount() {

    theWorkflowWasStartedBy("c7-retired");
    final var processService = aNodeWithoutAHint();

    assertThrows(
        WorkflowNotFoundException.class,
        () -> processService.correlateMessage(AGGREGATE, "PaymentReceived", null));
    assertTrue(scheduled.isEmpty());
    assertEquals(List.of("null"), adapter.workflowIdsHanded,
        "the id of the dropped adapter's workflow is not handed on");

  }

  @Test
  @DisplayName("A retried start whose start row names the adapter starts nothing and asks nobody")
  public void aRetriedStartWithARowStartsNothing() {

    theWorkflowWasStartedBy(ADAPTER);
    final var processService = aNodeWithoutAHint();

    processService.executePhaseTwo(PhaseOperation.START_WORKFLOW, AGGREGATE, ADAPTER, Map.of(), true);

    assertEquals(0, adapter.phaseTwoRunsOf(PhaseOperation.START_WORKFLOW));
    assertEquals(0, adapter.redispatchProbes.get(), "the row answered, so the search was not asked");

  }

  @Test
  @DisplayName("A retried start without a start row is probed as before")
  public void aRetriedStartWithoutARowIsProbed() {

    theWorkflowWasStartedBy("c7-retired");
    final var processService = aNodeWithoutAHint();

    processService.executePhaseTwo(PhaseOperation.START_WORKFLOW, AGGREGATE, ADAPTER, Map.of(), true);

    assertEquals(1, adapter.redispatchProbes.get());
    assertEquals(1, adapter.phaseTwoRunsOf(PhaseOperation.START_WORKFLOW), "the probe said unknown, so it starts");

  }

  @Test
  @DisplayName("A start is planned together with the moment it was planned")
  public void aStartCarriesItsPlanningMoment() {

    final var before = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    aNodeWithoutAHint().startWorkflow(AGGREGATE);

    final var start = scheduled.getFirst();
    assertEquals(PhaseOperation.START_WORKFLOW.name(), start.operation());
    final var plannedAt = Instant.parse(start.args().get(PhaseTwoCall.ARG_PLANNED_AT));
    assertFalse(plannedAt.isBefore(before));
    assertEquals(
        Optional.of("START_WORKFLOW|%s|%s|%s".formatted(MODULE, PROCESS, AGGREGATE)),
        start.idempotencyKey(),
        "the moment does not take part in the key, so a second planning of the same start is still a duplicate");

  }

  @Test
  @DisplayName("A retried start whose start row was written after it was planned starts nothing")
  public void aRetriedStartWithAYoungerRowStartsNothing() {

    final var plannedAt = Instant.now().minusSeconds(5);
    theWorkflowWasStartedAt(ADAPTER, plannedAt.plusMillis(200));
    final var processService = aNodeWithoutAHint();

    processService.executePhaseTwo(PhaseOperation.START_WORKFLOW, AGGREGATE, ADAPTER, plannedAt(plannedAt), true);

    assertEquals(0, adapter.phaseTwoRunsOf(PhaseOperation.START_WORKFLOW));
    assertEquals(0, adapter.redispatchProbes.get(), "the row answered, so the search was not asked");

  }

  @Test
  @DisplayName("A retried second start of an aggregate does not count the row of the first workflow and starts")
  public void aRetriedSecondStartDoesNotCountTheFirstWorkflow() {

    final var plannedAt = Instant.now();
    theWorkflowWasStartedAt(ADAPTER, plannedAt.minus(java.time.Duration.ofDays(2)));
    final var processService = aNodeWithoutAHint();

    processService.executePhaseTwo(PhaseOperation.START_WORKFLOW, AGGREGATE, ADAPTER, plannedAt(plannedAt), true);

    assertEquals(
        List.of(Optional.of(plannedAt)),
        adapter.planningMomentsHanded,
        "the row belongs to the earlier workflow, so the adapter is asked about the workflows started since");
    assertEquals(1, adapter.phaseTwoRunsOf(PhaseOperation.START_WORKFLOW), "the probe said unknown, so it starts");

  }

  @Test
  @DisplayName("A retried start planned before the moment was recorded counts every row, as before")
  public void aRetriedStartWithoutAPlanningMomentCountsTheRow() {

    theWorkflowWasStartedAt(ADAPTER, Instant.now().minus(java.time.Duration.ofDays(2)));
    final var processService = aNodeWithoutAHint();

    processService.executePhaseTwo(PhaseOperation.START_WORKFLOW, AGGREGATE, ADAPTER, Map.of(), true);

    assertEquals(0, adapter.phaseTwoRunsOf(PhaseOperation.START_WORKFLOW));
    assertTrue(adapter.planningMomentsHanded.isEmpty());

  }

  @Test
  @DisplayName("A retried start without a start row hands the probe the moment it was planned")
  public void aRetriedStartHandsThePlanningMomentToTheProbe() {

    final var plannedAt = Instant.now();
    adapter.answer = WorkflowAwareness.COMPLETED;
    final var processService = aNodeWithoutAHint();

    processService.executePhaseTwo(PhaseOperation.START_WORKFLOW, AGGREGATE, ADAPTER, plannedAt(plannedAt), true);
    processService
        .executePhaseTwo(
            PhaseOperation.START_WORKFLOW,
            AGGREGATE,
            ADAPTER,
            Map.of(PhaseTwoCall.ARG_PLANNED_AT, "not a moment"),
            true);

    assertEquals(List.of(Optional.of(plannedAt), Optional.empty()), adapter.planningMomentsHanded);
    assertEquals(0, adapter.phaseTwoRunsOf(PhaseOperation.START_WORKFLOW), "the probe knows a workflow since then");

  }

  private TaskDelivery theUserTaskWasLeftOpenBy(
      final String adapterId) {

    final var row = new TaskDelivery(
        "delivery-of-"
            + TASK, adapterId, MODULE, PROCESS, AGGREGATE, WORKFLOW, "approve", "Approve", TASK, "COMPLETION_PENDING", null, null, Instant
                .now(), null, "USER_TASK");
    deliveryLog.record(row);
    return row;

  }

  @Test
  @DisplayName("Phase two of a task-scoped push is handed the row of the task the adapter left open")
  public void aTaskScopedPushIsHandedTheRowOfItsTask() {

    theWorkflowWasStartedBy(ADAPTER);
    final var row = theUserTaskWasLeftOpenBy(ADAPTER);
    adapter.answer = WorkflowAwareness.ACTIVE;
    final var processService = aNodeWithoutAHint();

    processService
        .executePhaseTwo(
            PhaseOperation.AGGREGATE_CHANGED,
            AGGREGATE,
            null,
            Map.of(PhaseTwoCall.ARG_TASK_ID, TASK),
            false);

    assertEquals(
        List.of(Optional.of(row)),
        adapter.taskRowsInPhaseTwo,
        "the row names the workflow the task runs in, so the adapter does not have to search for it");

  }

  @Test
  @DisplayName("Phase two of a task-scoped push is not handed a row another adapter wrote, nor a row of no task")
  public void aRowOfAnotherAdapterOrOfNoTaskIsNotHandedOver() {

    theWorkflowWasStartedBy(ADAPTER);
    theUserTaskWasLeftOpenBy("c7-retired");
    adapter.answer = WorkflowAwareness.ACTIVE;
    final var processService = aNodeWithoutAHint();

    processService
        .executePhaseTwo(
            PhaseOperation.AGGREGATE_CHANGED,
            AGGREGATE,
            null,
            Map.of(PhaseTwoCall.ARG_TASK_ID, TASK),
            false);
    processService.executePhaseTwo(PhaseOperation.AGGREGATE_CHANGED, AGGREGATE, null, Map.of(), false);

    assertEquals(
        List.of(Optional.empty(), Optional.empty()),
        adapter.taskRowsInPhaseTwo,
        "ids another BPMS gave its task mean nothing to this adapter, and a global push names no task");

  }

}
