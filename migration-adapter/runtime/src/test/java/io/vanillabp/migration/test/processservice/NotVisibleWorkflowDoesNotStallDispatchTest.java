package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.migration.test.TestPhaseOperations;

/**
 * One workflow which its BPMS has not made searchable yet used to hold up every other entry
 * of the same store. The dispatch waited out the visibility window of the adapter, ten
 * seconds on Camunda 8. It did so on the one thread which dispatched everything back then.
 * So a burst of "start, then correlate" pairs stalled in batches, although the workflows in
 * it had nothing to do with each other.
 * <p>
 * This test holds what happens instead. The entry is handed back with the window as its due
 * time, and the lane goes on to the next entry. The store does not count that as an
 * attempt. A workflow which never becomes visible is ended by the time since the entry was
 * written, <code>vanillabp.outbox.wait-for-visibility-at-most</code>.
 * <code>WaitingForAReadModelUsesNoAttemptsTest</code> holds that for the relational
 * store.
 */
@ExtendWith(SuppressOutputExtension.class)
public class NotVisibleWorkflowDoesNotStallDispatchTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "test-adapter";

  /**
   * The window which an adapter like the one of Camunda 8 reports. It is short enough for a
   * test, and long enough that waiting it out would be plain to see.
   */
  private static final Duration WINDOW = Duration.ofSeconds(2);

  private static final String NOT_VISIBLE_YET = "42";

  private static final String FINDABLE = "43";

  /**
   * An eventually consistent BPMS. It reports the workflow of {@link #FINDABLE}. It has not
   * caught up with the one of {@link #NOT_VISIBLE_YET}, whatever it is asked.
   */
  private static class LaggingAdapter implements MigratableProcessService<Object> {

    private final List<String> correlated = new ArrayList<>();

    /**
     * Which workflow id the core handed to the window question, in the order it asked.
     */
    protected final List<String> askedAbout = new ArrayList<>();

    /**
     * How often the BPMS was asked about the workflow it has not caught up with. Waiting
     * for that workflow means asking again every twenty milliseconds. So this number says
     * whether anybody waited.
     */
    private final java.util.concurrent.atomic.AtomicInteger probesOfTheLaggingWorkflow = new java.util.concurrent.atomic.AtomicInteger();

    @Override
    public String getAdapterId() {
      return ADAPTER;
    }

    @Override
    public WorkflowVisibilityDelay workflowVisibilityDelay() {
      return new WorkflowVisibilityDelay(WINDOW, Duration.ofMillis(20));
    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {

      if (FINDABLE.equals(workflowAggregateId)) {
        return WorkflowAwareness.ACTIVE;
      }
      probesOfTheLaggingWorkflow.incrementAndGet();
      return WorkflowAwareness.UNKNOWN_TO_BPMS;

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
    public Map<PhaseOperation, PhaseOperationHandler<Object>> phaseOperations() {

      return TestPhaseOperations.with(
          PhaseOperation.CORRELATE_MESSAGE,
          PhaseOperationHandler.of(
              request -> {
              },
              request -> correlated.add(String.valueOf(request.workflowAggregateId()))));

    }

  }

  private static MigrationAdapterProperties properties() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    return properties;

  }

  /**
   * A process service whose cache knows that both workflows sit in the lagging adapter.
   * With the hint, an unknown answer is worth repeating, and it does not mean a stale
   * entry.
   */
  private static MigrationProcessService<Object> serviceKnowingBothWorkflows(
      final LaggingAdapter adapter) {

    return serviceKnowingBothWorkflows(adapter, null);

  }

  /**
   * The same, but the hint also holds the id which the BPMS gave the workflow that is not
   * visible yet. A delivery or a start leaves this behind. It lets the adapter answer its
   * window for that one workflow.
   */
  private static MigrationProcessService<Object> serviceKnowingBothWorkflows(
      final LaggingAdapter adapter,
      final String workflowIdOfTheLaggingOne) {

    @SuppressWarnings("unchecked")
    final AggregatePersistenceAware<Object> persistence = mock(AggregatePersistenceAware.class);
    lenient().when(persistence.getAggregateId(any())).thenReturn(NOT_VISIBLE_YET);

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, NOT_VISIBLE_YET, ADAPTER, workflowIdOfTheLaggingOne);
    cache.put(MODULE, PROCESS, FINDABLE, ADAPTER);

    final PhaseTwoOutboxResolver resolver = new PhaseTwoOutboxResolver() {

      @Override
      public PhaseTwoOutbox resolveFor(
          final Class<?> workflowAggregateClass) {
        return mock(PhaseTwoOutbox.class);
      }

      @Override
      public String remediesDescription() {
        return "- add a store, or";
      }

      @Override
      public java.util.Collection<PhaseTwoOutbox> allStores() {
        return java.util.List.of();
      }

    };

    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Object.class)
        .properties(properties())
        .aggregatePersistence(persistence)
        .processServices(List.of(adapter))
        .phaseTwoOutboxResolver(resolver)
        .workflowAdapterCache(cache)
        .build();

  }

  private static void dispatchCorrelation(
      final MigrationProcessService<Object> service,
      final String aggregateId) {

    service.executePhaseTwo(
        PhaseOperation.CORRELATE_MESSAGE,
        aggregateId,
        null,
        Map.of(PhaseTwoCall.ARG_MESSAGE_NAME, "ItemShipped", PhaseTwoCall.ARG_CORRELATION_ID, "item-"
            + aggregateId),
        false);

  }

  @Test
  @DisplayName("An entry waiting for visibility does not hold the entry of another workflow")
  public void anEntryWaitingForVisibilityDoesNotHoldTheOthers() {

    final var adapter = new LaggingAdapter();
    final var service = serviceKnowingBothWorkflows(adapter);

    final var retryLater = assertThrows(
        PhaseTwoRetryLater.class,
        () -> dispatchCorrelation(service, NOT_VISIBLE_YET),
        "the entry of a workflow which is not searchable yet has to come back to the store");
    // the entry says when asking again can help. That is the own window of the adapter
    assertEquals(WINDOW, retryLater.getRetryAfter());

    // the very next entry, of a workflow which the same BPMS reports, on the same thread
    dispatchCorrelation(service, FINDABLE);

    assertEquals(List.of(FINDABLE), adapter.correlated);
    // a thread which waited out the window would have asked the BPMS a hundred times, once
    // every twenty milliseconds. A wall clock would read the same claim from a worse
    // instrument. A machine with several builds stops this JVM for seconds at a time, and a
    // stopped JVM looks like a thread which waited
    assertEquals(
        1,
        adapter.probesOfTheLaggingWorkflow.get(),
        "the dispatch asks once and gives the entry back instead of waiting for the read model");

  }

  @Test
  @DisplayName("An adapter answering per workflow decides how soon its entry comes back")
  public void theDueTimeIsTheWindowOfThatWorkflow() {

    // the adapter knows this workflow by its own id, and its engine says the workflow is
    // gone. So nothing is gained by giving the read model the window a young workflow
    // needs
    final var shortWindow = Duration.ofMillis(900);
    final var adapter = new LaggingAdapter() {

      @Override
      public WorkflowVisibilityDelay workflowVisibilityDelay(
          final String workflowId) {

        askedAbout.add(workflowId);
        return "workflow-77".equals(workflowId)
            ? new WorkflowVisibilityDelay(shortWindow, Duration.ofMillis(20))
            : super.workflowVisibilityDelay(workflowId);

      }

    };
    final var service = serviceKnowingBothWorkflows(adapter, "workflow-77");

    final var retryLater = assertThrows(
        PhaseTwoRetryLater.class,
        () -> dispatchCorrelation(service, NOT_VISIBLE_YET));

    assertEquals(
        shortWindow,
        retryLater.getRetryAfter(),
        "the entry comes back when this workflow is worth asking about again");
    assertEquals(
        List.of("workflow-77"),
        adapter.askedAbout,
        "and the adapter was told which workflow the window is for");

  }

  @Test
  @DisplayName("A workflow which never becomes visible is handed back with the same window every time")
  public void aWorkflowWhichNeverBecomesVisibleKeepsItsWindow() {

    final var adapter = new LaggingAdapter();
    final var service = serviceKnowingBothWorkflows(adapter);

    // more repetitions than the stores allow failed attempts by default. The core gives the
    // entry back the same way each time. The store decides when the waiting is over, by the
    // time the entry has waited, and not by these answers
    final var moreThanTheAttemptsAllowed = io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties
        .builder()
        .build()
        .getBlockAfterAttempts() + 1;
    for (var repetition = 1; repetition <= moreThanTheAttemptsAllowed; repetition++) {
      final var retryLater = assertThrows(
          PhaseTwoRetryLater.class,
          () -> dispatchCorrelation(service, NOT_VISIBLE_YET));
      // the window belongs to the adapter and stays short. The growing backoff of a failing
      // entry is NOT applied to it
      assertEquals(WINDOW, retryLater.getRetryAfter(), "repetition "
          + repetition
          + " asked for another due time");
    }

  }

}
