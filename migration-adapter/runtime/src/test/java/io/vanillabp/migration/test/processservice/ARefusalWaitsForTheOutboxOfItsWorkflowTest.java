package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseOperationNotSupported;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Phase one asks the BPMS as it is now. An operation of the same workflow which still waits
 * in the outbox has not reached the BPMS. So a refusal of phase one counts only where the
 * store says that nothing of the workflow waits. Otherwise the operation is planned, and
 * phase two runs after the waiting one. See {@code DECISIONS.pending/2021.md}.
 * <p>
 * The adapter here plays an embedded engine with a user task and a message after it. Its
 * phase one of a correlation refuses while the engine does not wait for the message, as
 * the Camunda 7 adapter does. The store keeps the order of one workflow's entries, as the
 * stores VanillaBP ships do.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ARefusalWaitsForTheOutboxOfItsWorkflowTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "embedded";

  private static final String AGGREGATE_ID = "4711";

  /** The sentence of the engine's refusal, which every case names. */
  private static final String REFUSAL = "Nothing waits for message 'ContractSigned'";

  /** An engine with a user task, and a message the workflow waits for after it. */
  private static final class Engine {

    private boolean waitsForTheMessage;

    private final List<String> events = new ArrayList<>();

    private boolean correlationNotSupported;

    Map<PhaseOperation, PhaseOperationHandler<Object>> phaseOperations() {

      return Map
          .of(
              PhaseOperation.COMPLETE_USER_TASK,
              PhaseOperationHandler
                  .of(
                      request -> {
                        // the task exists, nothing to refuse
                      },
                      request -> {
                        waitsForTheMessage = true;
                        events.add("completed "
                            + request.taskId());
                      }),
              PhaseOperation.CORRELATE_MESSAGE,
              PhaseOperationHandler
                  .of(
                      request -> {
                        if (correlationNotSupported) {
                          throw new PhaseOperationNotSupported(
                              ADAPTER, PhaseOperation.CORRELATE_MESSAGE, MODULE, PROCESS, Map.of());
                        }
                        if (!waitsForTheMessage) {
                          throw new IllegalStateException(
                              "Nothing waits for message '%s'".formatted(request.messageName()));
                        }
                      },
                      request -> events.add("correlated "
                          + request.messageName())));

    }

  }

  /**
   * Keeps what it was given in the order it was given, and answers whether something of a
   * workflow waits. Nothing is dispatched until a test says so.
   */
  private static class OrderedOutbox implements PhaseTwoOutbox {

    final List<PhaseTwoCall> pending = new ArrayList<>();

    final Set<String> questions = new HashSet<>();

    @Override
    public boolean schedule(
        final PhaseTwoCall call) {

      pending.add(call);
      return true;

    }

    @Override
    public Optional<Boolean> hasPendingCallsOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {

      questions.add(workflowAggregateId);
      return Optional
          .of(pending
              .stream()
              .anyMatch(call -> call.workflowModuleId().equals(workflowModuleId) && call.bpmnProcessId()
                  .equals(bpmnProcessId) && call.workflowAggregateId().equals(workflowAggregateId)));

    }

    void dispatchAll(
        final MigrationProcessService<Object> service) {

      while (!pending.isEmpty()) {
        final var call = pending.removeFirst();
        service
            .executePhaseTwo(
                PhaseOperation.coreOperation(call.operation()).orElseThrow(),
                call.workflowAggregateId(),
                call.adapterId(),
                call.args(),
                false);
      }

    }

  }

  /** A store written before the question existed. It cannot say. */
  private static final class OutboxWhichCannotSay implements PhaseTwoOutbox {

    final List<PhaseTwoCall> pending = new ArrayList<>();

    @Override
    public boolean schedule(
        final PhaseTwoCall call) {

      pending.add(call);
      return true;

    }

  }

  /** A store whose answer fails. */
  private static final class OutboxWhichFailsToAnswer extends OrderedOutbox {

    @Override
    public Optional<Boolean> hasPendingCallsOf(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {

      throw new IllegalStateException("the database is away");

    }

  }

  private static MigrationProcessService<Object> serviceFor(
      final Engine engine,
      final PhaseTwoOutbox outbox) {

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Object> adapter = mock(MigratableProcessService.class);
    lenient().when(adapter.getAdapterId()).thenReturn(ADAPTER);
    lenient().when(adapter.phaseOperations()).thenAnswer(invocation -> engine.phaseOperations());
    lenient()
        .when(adapter.awarenessOfUserTask(any(), any(), any()))
        .thenReturn(WorkflowAwareness.ACTIVE);
    lenient()
        .when(adapter.awarenessOfWorkflow(any(), any(), any()))
        .thenReturn(WorkflowAwareness.ACTIVE);
    // the election hands the workflow id to a probe about a workflow. A mock does not run
    // the default which drops it
    lenient()
        .when(adapter.awarenessOfWorkflow(any(), any(), any(), any()))
        .thenReturn(WorkflowAwareness.ACTIVE);

    @SuppressWarnings("unchecked")
    final AggregatePersistenceAware<Object> persistence = mock(AggregatePersistenceAware.class);
    lenient().when(persistence.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    lenient().when(persistence.getAggregateId(any())).thenReturn(AGGREGATE_ID);

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();

    final PhaseTwoOutboxResolver resolver = new PhaseTwoOutboxResolver() {

      @Override
      public PhaseTwoOutbox resolveFor(
          final Class<?> workflowAggregateClass) {
        return outbox;
      }

      @Override
      public String remediesDescription() {
        return "- add a store, or";
      }

      @Override
      public java.util.Collection<PhaseTwoOutbox> allStores() {
        return List.of(outbox);
      }

    };

    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Object.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(adapter))
        .phaseTwoOutboxResolver(resolver)
        .build();

  }

  @Test
  @DisplayName("A message correlated right after completing the user task is planned and follows the completion")
  public void aCorrelationRightAfterACompletionIsPlanned() {

    final var engine = new Engine();
    final var outbox = new OrderedOutbox();
    final var service = serviceFor(engine, outbox);
    final var aggregate = new Object();

    service.completeUserTask(aggregate, "utask-1");
    // the completion has not reached the engine. It does not wait for the message yet
    final var attached = service.correlateMessage(aggregate, "ContractSigned", null);

    assertSame(aggregate, attached);
    assertEquals(2, outbox.pending.size(), "both operations are planned");
    outbox.dispatchAll(service);
    assertEquals(List.of("completed utask-1", "correlated ContractSigned"), engine.events);

  }

  @Test
  @DisplayName("A refusal stands where nothing of the workflow waits in the outbox")
  public void aRefusalStandsWhereNothingWaits() {

    final var outbox = new OrderedOutbox();
    final var service = serviceFor(new Engine(), outbox);

    final var refusal = assertThrows(
        IllegalStateException.class,
        () -> service.correlateMessage(new Object(), "ContractSigned", null));

    assertTrue(refusal.getMessage().contains(REFUSAL), refusal.getMessage());
    assertTrue(outbox.pending.isEmpty(), "nothing is planned");
    assertEquals(Set.of(AGGREGATE_ID), outbox.questions, "the store was asked about this workflow");

  }

  @Test
  @DisplayName("An entry of another workflow does not hold a refusal back")
  public void anEntryOfAnotherWorkflowDoesNotCount() {

    final var outbox = new OrderedOutbox();
    outbox.pending.add(PhaseTwoCall.of(PhaseOperation.COMPLETE_USER_TASK, MODULE, PROCESS, "another", null,
        Map.of(PhaseTwoCall.ARG_TASK_ID, "utask-9")));
    final var service = serviceFor(new Engine(), outbox);

    final var refusal = assertThrows(
        IllegalStateException.class,
        () -> service.correlateMessage(new Object(), "ContractSigned", null));

    assertTrue(refusal.getMessage().contains(REFUSAL), refusal.getMessage());

  }

  @Test
  @DisplayName("A store which cannot say keeps the refusal, as before")
  public void aStoreWhichCannotSayKeepsTheRefusal() {

    final var outbox = new OutboxWhichCannotSay();
    final var service = serviceFor(new Engine(), outbox);
    service.completeUserTask(new Object(), "utask-1");

    final var refusal = assertThrows(
        IllegalStateException.class,
        () -> service.correlateMessage(new Object(), "ContractSigned", null));

    assertTrue(refusal.getMessage().contains(REFUSAL), refusal.getMessage());
    assertEquals(1, outbox.pending.size(), "only the completion is planned");

  }

  @Test
  @DisplayName("A store whose answer fails keeps the refusal and names the failure beside it")
  public void aFailedAnswerKeepsTheRefusal() {

    final var outbox = new OutboxWhichFailsToAnswer();
    final var service = serviceFor(new Engine(), outbox);
    service.completeUserTask(new Object(), "utask-1");

    final var refusal = assertThrows(
        IllegalStateException.class,
        () -> service.correlateMessage(new Object(), "ContractSigned", null));

    assertTrue(refusal.getMessage().contains(REFUSAL), refusal.getMessage());
    assertEquals(1, refusal.getSuppressed().length);
    assertEquals("the database is away", refusal.getSuppressed()[0].getMessage());

  }

  @Test
  @DisplayName("An operation the adapter does not support is refused whatever waits")
  public void anUnsupportedOperationIsAlwaysRefused() {

    final var engine = new Engine();
    engine.correlationNotSupported = true;
    final var outbox = new OrderedOutbox();
    final var service = serviceFor(engine, outbox);
    service.completeUserTask(new Object(), "utask-1");

    assertThrows(
        PhaseOperationNotSupported.class,
        () -> service.correlateMessage(new Object(), "ContractSigned", null));
    assertTrue(outbox.questions.isEmpty(), "the store is not even asked");

  }

}
