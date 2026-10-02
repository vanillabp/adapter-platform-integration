package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskKind;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.migration.test.RecordedPhaseOperations;
import io.vanillabp.spi.process.TaskNotFoundException;
import io.vanillabp.spi.service.TaskId;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * An adapter which deduplicates nothing still writes down which BPMS holds the task and
 * which kind of id its id is.
 * <p>
 * Camunda 7 is the adapter this was measured on. A transaction of the engine creates every
 * user task the token reaches and the id comes into being while it is created, so the
 * adapter reports no delivery id: there is nothing a repetition could be recognised by.
 * That used to mean no row at all, and with the row went the two answers which have nothing
 * to do with repetitions. So <code>completeTask</code> with the id of a user task got the
 * list of three guesses on Camunda 7, which is exactly the mistake the kind of task was
 * written down for.
 * <p>
 * What runs here is the whole way: a delivery goes in through the registry, the handler
 * leaves the task open, and the application then hands that user task's id to the operation
 * which asks a BPMS about a task. No BPMS knows the id, each of them says so, and the
 * failure names what the id is.
 * <p>
 * This is the platform's half of the proof. The other half needs an engine and belongs to
 * the Camunda 7 repository, where the adapter really reports no delivery id - here the
 * adapter is a double which reports none.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ADeliveryNobodyDeduplicatesStillNamesTheKindTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "c7";

  private static final String AGGREGATE = "4711";

  /** The task definition of the user task the handler leaves open. */
  private static final String TASK = "approve";

  /** The id of the user task, which is what the mistake hands to a task operation. */
  private static final String USER_TASK = "user-task-1";

  public static class Aggregate {

    @Getter
    String id;

    int invocations;

  }

  static class InMemoryPersistence implements AggregatePersistenceAware<Aggregate> {

    final Map<Object, Aggregate> aggregates = new HashMap<>();

    @Override
    public Class<Aggregate> getAggregateClass() {
      return Aggregate.class;
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
        final Aggregate aggregate) {
      return aggregate.id;
    }

    @Override
    public Aggregate save(
        final Aggregate aggregate) {
      aggregates.put(aggregate.id, aggregate);
      return aggregate;
    }

    @Override
    public Aggregate loadById(
        final Object aggregateId) {
      return aggregates.get(aggregateId);
    }

  }

  static class TransactionRunnerStub implements TransactionRunner {

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {
      return work.get();
    }

    @Override
    public <T> T inCurrent(
        final Supplier<T> work) {
      return requireNew(work);
    }

    @Override
    public boolean isRollbackOnly() {
      return false;
    }

  }

  /** A delivery log in memory which can be queried by the task, like the stores VanillaBP ships. */
  static class InMemoryDeliveryLog implements TaskDeliveryLog {

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
          .filter(record -> workflowModuleId.equals(record.workflowModuleId()))
          .filter(record -> bpmnProcessId.equals(record.bpmnProcessId()))
          .filter(record -> workflowAggregateId.equals(record.workflowAggregateId()))
          .filter(record -> taskId.equals(record.taskId()))
          .filter(record -> "COMPLETION_PENDING".equals(record.outcome()))
          .findFirst();

    }

  }

  /**
   * An adapter of an embedded engine: it repeats no delivery, so it reports no delivery id,
   * and its BPMS knows none of the ids asked about - which is the answer every BPMS gives
   * for an id of the other kind.
   */
  static class AnEmbeddedEngine implements MigratableProcessService<Aggregate> {

    final AtomicInteger probes = new AtomicInteger();

    final RecordedPhaseOperations<Aggregate> operations = new RecordedPhaseOperations<>();

    @Override
    public String getAdapterId() {
      return ADAPTER;
    }

    @Override
    public Map<PhaseOperation, PhaseOperationHandler<Aggregate>> phaseOperations() {
      return operations.operations();
    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {

      probes.incrementAndGet();
      return WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Override
    public WorkflowAwareness awarenessOfUserTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {

      probes.incrementAndGet();
      return WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<Aggregate> aggregatePersistence,
        final Object workflowAggregateId) {

      probes.incrementAndGet();
      return WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Override
    public boolean deliversTasksAtLeastOnce() {
      return false;
    }

  }

  /** The outbox, reduced to what it is here: what phase two was planned with. */
  static class CapturingOutbox implements PhaseTwoOutbox {

    final List<PhaseTwoCall> scheduled = new ArrayList<>();

    @Override
    public boolean schedule(
        final PhaseTwoCall call) {
      return scheduled.add(call);
    }

  }

  public static class TestWorkflowService {

    /**
     * A user task the application completes later - the only kind which can be left open.
     */
    @WorkflowTask(taskDefinition = TASK)
    public void approve(
        final Aggregate aggregate,
        @TaskId final String taskId) {

      aggregate.invocations++;

    }

  }

  private final InMemoryPersistence persistence = new InMemoryPersistence();

  private final InMemoryDeliveryLog deliveryLog = new InMemoryDeliveryLog();

  private final CapturingOutbox outbox = new CapturingOutbox();

  private final AnEmbeddedEngine adapter = new AnEmbeddedEngine();

  private static MigrationAdapterProperties properties() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    return properties;

  }

  private MigrationProcessService<Aggregate> service() {

    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Aggregate.class)
        .properties(properties())
        .aggregatePersistence(persistence)
        .processServices(List.of(adapter))
        .phaseTwoOutboxResolver(new PhaseTwoOutboxResolver() {

          @Override
          public PhaseTwoOutbox resolveFor(
              final Class<?> workflowAggregateClass) {
            return outbox;
          }

          @Override
          public String remediesDescription() {
            return "";
          }

          @Override
          public java.util.Collection<PhaseTwoOutbox> allStores() {
            return List.of(outbox);
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

  private WorkflowTaskRegistry registry(
      final MigrationProcessService<Aggregate> processService) {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub());
    registry
        .registerWorkflowService(
            MODULE,
            PROCESS,
            TestWorkflowService.class,
            TestWorkflowService::new,
            type -> null,
            processService);
    return registry;

  }

  private Aggregate storeAggregate() {

    final var aggregate = new Aggregate();
    aggregate.id = AGGREGATE;
    persistence.aggregates.put(AGGREGATE, aggregate);
    return aggregate;

  }

  /**
   * A delivery of the embedded engine: it names the task and the kind of task, and it
   * reports no delivery id at all.
   */
  private TaskInvocationContext aUserTaskDelivery(
      final TaskKind kind) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getTaskDefinition() {
        return TASK;
      }

      @Override
      public String getWorkflowAggregateId() {
        return AGGREGATE;
      }

      @Override
      public String getTaskId() {
        return USER_TASK;
      }

      @Override
      public TaskKind getTaskKind() {
        return kind;
      }

      @Override
      public String getDeliveryId() {
        return null;
      }

    };

  }

  @Test
  @DisplayName("A delivery without an id writes a row naming the adapter and the kind")
  public void aDeliveryWithoutAnIdIsStillWrittenDown() {

    final var testee = registry(service());
    storeAggregate();

    final var outcome = testee.invokeWorkflowTask(MODULE, PROCESS, aUserTaskDelivery(TaskKind.USER_TASK));

    assertEquals(WorkflowTaskOutcome.Kind.COMPLETION_PENDING, outcome.kind());
    assertEquals(1, deliveryLog.records.size(), "the delivery was written down");
    final var record = deliveryLog.records.values().iterator().next();
    assertEquals(ADAPTER, record.adapterId(), "the row says which BPMS holds the task");
    assertEquals(TaskKind.USER_TASK.name(), record.taskKind(), "and which kind of id its id is");
    assertEquals(USER_TASK, record.taskId());
    assertTrue(
        record.deliveryKey().contains("(not-deduplicated)"),
        "and its key does not look as if the delivery were deduplicable: "
            + record.deliveryKey());

  }

  @Test
  @DisplayName("completeTask with the id of such a user task says what the id is")
  public void theMixUpIsAnsweredWithWhatTheIdIs() {

    final var testee = service();
    final var aggregate = storeAggregate();
    registry(testee).invokeWorkflowTask(MODULE, PROCESS, aUserTaskDelivery(TaskKind.USER_TASK));

    final var failure = assertThrows(
        TaskNotFoundException.class,
        () -> testee.completeTask(aggregate, USER_TASK));

    assertTrue(
        failure.getMessage().contains("'%s' is the id of a user task".formatted(USER_TASK)),
        failure.getMessage());
    assertTrue(
        failure.getMessage().contains("asks about a user task is completeUserTask"),
        failure.getMessage());
    assertFalse(
        failure.getMessage().contains("Likely causes"),
        "the row knows what the id is, so nothing is guessed: "
            + failure.getMessage());
    assertTrue(outbox.scheduled.isEmpty(), "nothing is planned for a command no BPMS can serve");

  }

  @Test
  @DisplayName("The user-task operation is still elected from such a row, and no BPMS is asked")
  public void theRightOperationIsElectedFromSuchARow() {

    final var testee = service();
    final var aggregate = storeAggregate();
    registry(testee).invokeWorkflowTask(MODULE, PROCESS, aUserTaskDelivery(TaskKind.USER_TASK));

    testee.completeUserTask(aggregate, USER_TASK);

    assertEquals(0, adapter.probes.get(), "the row answers the election, as a deduplicable one does");
    assertEquals(1, outbox.scheduled.size(), "phase two is planned");

  }

  @Test
  @DisplayName("An adapter which names no kind writes the row and claims nothing with it")
  public void anAdapterWhichNamesNoKindClaimsNothing() {

    final var testee = service();
    final var aggregate = storeAggregate();
    registry(testee).invokeWorkflowTask(MODULE, PROCESS, aUserTaskDelivery(null));

    final var record = deliveryLog.records.values().iterator().next();
    assertEquals(ADAPTER, record.adapterId(), "the row is written and names the adapter");
    assertNull(record.taskKind(), "and it names no kind, because the adapter named none");

    // the mix-up is not caught here and never was: a row which says nothing about the kind
    // contradicts nothing, so the command is elected from it exactly as it always was
    testee.completeTask(aggregate, USER_TASK);

    assertEquals(0, adapter.probes.get());
    assertEquals(1, outbox.scheduled.size());

  }

}
