package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.WorkflowNotFoundException;

/**
 * Pushing a changed workflow-aggregate to the BPMS (story 44). The shape is the one
 * of message correlation - save, probe, phase one or outbox - so these tests cover
 * what is different: the task id deciding the scope, the missing idempotency key and
 * an ended workflow being a warning instead of a failure.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AggregateChangedTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  /**
   * Records what the adapter was asked to push, and in which phase.
   */
  static class RecordingAdapter extends SendSignalTest.RecordingAdapter {

    record Push(Object aggregate, String taskId) {
    }

    final List<Push> phaseOnePushes = new LinkedList<>();

    final List<Push> phaseTwoPushes = new LinkedList<>();

    WorkflowAwareness awareness = WorkflowAwareness.ACTIVE;

    RecordingAdapter(
        final String adapterId,
        final boolean twoPhase) {
      super(adapterId, twoPhase);
    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final Object workflowAggregateId) {
      return awareness;
    }

    @Override
    public void aggregateChangedPhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate,
        final String taskId) {
      phaseOnePushes.add(new Push(workflowAggregate, taskId));
    }

    @Override
    public void aggregateChangedPhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String taskId) {
      phaseTwoPushes.add(new Push(workflowAggregateId, taskId));
    }

  }

  /**
   * Hands back the aggregate it was given and reports a fixed ID - the core saves
   * before it probes, which is what the tests rely on.
   */
  static class PersistenceStub implements AggregatePersistenceAware<Object> {

    final List<Object> saved = new LinkedList<>();

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
      saved.add(workflowAggregate);
      return workflowAggregate;
    }

    @Override
    public Object getAggregateId(
        final Object workflowAggregate) {
      return "42";
    }

  }

  static class RecordingOutbox implements PhaseTwoOutbox {

    final List<PhaseTwoCall> scheduled = new LinkedList<>();

    @Override
    public boolean schedule(
        final PhaseTwoCall call) {
      scheduled.add(call);
      return true;
    }

  }

  private final PersistenceStub persistence = new PersistenceStub();

  private MigrationProcessService<Object> processService(
      final List<MigratableProcessService<Object>> adapters,
      final PhaseTwoOutbox outbox) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of("first-adapter", AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of("first-adapter"))
        .build();
    properties.validateAndLink();

    PhaseTwoOutboxResolver resolver = null;
    if (outbox != null) {
      resolver = new PhaseTwoOutboxResolver() {

        @Override
        public PhaseTwoOutbox resolveFor(
            final Class<?> workflowAggregateClass) {
          return outbox;
        }

        @Override
        public String remediesDescription() {
          return "test";
        }

      };
    }

    return new MigrationProcessService<Object>(
        MODULE, PROCESS, Object.class, properties, persistence, adapters, resolver);

  }

  @Test
  @DisplayName("An embedded BPMS is given the saved aggregate inside the transaction")
  public void embeddedBpmsIsPushedInPhaseOne() {

    final var adapter = new RecordingAdapter("first-adapter", false);
    final var aggregate = new Object();

    final var attached = processService(List.of(adapter), null).aggregateChanged(aggregate, null);

    assertSame(aggregate, attached);
    // the aggregate is saved BEFORE the BPMS is told - the caller changed it, and
    // that change is what the BPMS is supposed to see
    assertEquals(List.of(aggregate), persistence.saved);
    assertEquals(1, adapter.phaseOnePushes.size());
    assertSame(aggregate, adapter.phaseOnePushes.getFirst().aggregate());
    // no task id: the workflow's global scope
    assertNull(adapter.phaseOnePushes.getFirst().taskId());

  }

  @Test
  @DisplayName("The task id travels to the adapter and into the outbox entry")
  public void theTaskIdDecidesTheScope() {

    final var adapter = new RecordingAdapter("first-adapter", true);
    final var outbox = new RecordingOutbox();

    processService(List.of(adapter), outbox).aggregateChanged(new Object(), "task-1");

    assertEquals("task-1", adapter.phaseOnePushes.getFirst().taskId());

    assertEquals(1, outbox.scheduled.size());
    final var call = outbox.scheduled.getFirst();
    assertEquals("AGGREGATE_CHANGED", call.operation());
    assertEquals("42", call.workflowAggregateId());
    assertEquals("task-1", call.args().get(PhaseTwoCall.ARG_TASK_ID));
    // no adapter id is persisted: the BPMS holding the workflow answers at dispatch
    // time, like every other probing operation
    assertNull(call.adapterId());
    // the values are read when the entry is dispatched, so a repeated dispatch
    // writes the then-current state - there is nothing to deduplicate
    assertTrue(call.idempotencyKey().isEmpty());

  }

  @Test
  @DisplayName("Without a task id the outbox entry carries none")
  public void aGlobalPushCarriesNoTaskId() {

    final var outbox = new RecordingOutbox();

    processService(List.of(new RecordingAdapter("first-adapter", true)), outbox)
        .aggregateChanged(new Object(), null);

    assertTrue(outbox.scheduled.getFirst().args().isEmpty());

  }

  @Test
  @DisplayName("An ended workflow is a warning, and the aggregate is saved either way")
  public void aCompletedWorkflowIsNoFailure() {

    final var adapter = new RecordingAdapter("first-adapter", false);
    adapter.awareness = WorkflowAwareness.COMPLETED;
    final var aggregate = new Object();

    final var attached = processService(List.of(adapter), null).aggregateChanged(aggregate, null);

    assertSame(aggregate, attached);
    assertEquals(List.of(aggregate), persistence.saved);
    assertTrue(adapter.phaseOnePushes.isEmpty());

  }

  @Test
  @DisplayName("A workflow no BPMS knows fails guiding - naming that the aggregate was saved")
  public void anUnknownWorkflowFailsGuiding() {

    final var adapter = new RecordingAdapter("first-adapter", false);
    adapter.awareness = WorkflowAwareness.UNKNOWN_TO_BPMS;

    final var exception = assertThrows(
        WorkflowNotFoundException.class,
        () -> processService(List.of(adapter), null).aggregateChanged(new Object(), null));

    assertTrue(exception.getMessage().contains("42"));
    assertTrue(exception.getMessage().contains("was saved"));

  }

  @Test
  @DisplayName("Phase two pushes through the BPMS holding the workflow")
  public void phaseTwoElectsByProbing() {

    final var adapter = new RecordingAdapter("first-adapter", true);

    processService(List.of(adapter), new RecordingOutbox()).aggregateChangedPhaseTwo("42", "task-1");

    assertEquals(1, adapter.phaseTwoPushes.size());
    assertEquals("42", adapter.phaseTwoPushes.getFirst().aggregate());
    assertEquals("task-1", adapter.phaseTwoPushes.getFirst().taskId());

  }

  @Test
  @DisplayName("A workflow gone by dispatch time consumes the entry instead of failing")
  public void phaseTwoTolerAtesAStaleEntry() {

    final var adapter = new RecordingAdapter("first-adapter", true);
    adapter.awareness = WorkflowAwareness.COMPLETED;

    processService(List.of(adapter), new RecordingOutbox()).aggregateChangedPhaseTwo("42", null);

    assertTrue(adapter.phaseTwoPushes.isEmpty());

  }

  @Test
  @DisplayName("An adapter whose BPMS cannot update a running instance says so")
  public void anAdapterWithoutSupportFailsGuiding() {

    final var adapter = new SendSignalTest.RecordingAdapter("first-adapter", false) {

      @Override
      public WorkflowAwareness awarenessOfWorkflow(
          final Object workflowAggregateId) {
        return WorkflowAwareness.ACTIVE;
      }

    };

    final var exception = assertThrows(
        UnsupportedOperationException.class,
        () -> processService(List.of(adapter), null).aggregateChanged(new Object(), null));

    assertTrue(exception.getMessage().contains("first-adapter"));
    assertTrue(exception.getMessage().contains(PROCESS));

  }

}
