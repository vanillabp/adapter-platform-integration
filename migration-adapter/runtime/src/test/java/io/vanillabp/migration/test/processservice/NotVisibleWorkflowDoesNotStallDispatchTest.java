package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
 * One workflow whose BPMS has not made it searchable yet used to hold every other entry
 * of the same store: the dispatch waited out the adapter's visibility window on the one
 * thread which dispatches everything, ten seconds on Camunda 8. So a burst of "start,
 * then correlate" pairs stalled in batches, and the workflows in it had nothing to do
 * with each other.
 * <p>
 * What happens instead is here: the entry is handed back with the window as its due
 * time, and the thread goes on to the next entry. The store counts the attempt like any
 * other, which is what ends a workflow that never becomes visible - after
 * <code>block-after-attempts</code> of them the entry is blocked, and with the defaults
 * that is ten attempts, one hundred seconds of a Camunda 8 window.
 */
@ExtendWith(SuppressOutputExtension.class)
public class NotVisibleWorkflowDoesNotStallDispatchTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "test-adapter";

  /**
   * The window a Camunda-8-like adapter reports, short enough for a test and long
   * enough that waiting it out would be plain to see.
   */
  private static final Duration WINDOW = Duration.ofSeconds(2);

  private static final String NOT_VISIBLE_YET = "42";

  private static final String FINDABLE = "43";

  /**
   * An eventually consistent BPMS: it reports the workflow of {@link #FINDABLE} and
   * has not caught up with the one of {@link #NOT_VISIBLE_YET}, whatever it is asked.
   */
  private static final class LaggingAdapter implements MigratableProcessService<Object> {

    private final List<String> correlated = new ArrayList<>();

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

      return FINDABLE.equals(workflowAggregateId)
          ? WorkflowAwareness.ACTIVE
          : WorkflowAwareness.UNKNOWN_TO_BPMS;

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
   * A process service whose cache knows both workflows sit in the lagging adapter -
   * the hint is what makes an unknown answer worth repeating instead of a stale entry.
   */
  private static MigrationProcessService<Object> serviceKnowingBothWorkflows(
      final LaggingAdapter adapter) {

    @SuppressWarnings("unchecked")
    final AggregatePersistenceAware<Object> persistence = mock(AggregatePersistenceAware.class);
    lenient().when(persistence.getAggregateId(any())).thenReturn(NOT_VISIBLE_YET);

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, NOT_VISIBLE_YET, ADAPTER);
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

    final var startedAt = System.nanoTime();

    final var retryLater = assertThrows(
        PhaseTwoRetryLater.class,
        () -> dispatchCorrelation(service, NOT_VISIBLE_YET),
        "the entry of a workflow which is not searchable yet has to come back to the store");
    // the entry says when asking again can help, and that is the adapter's own window
    assertEquals(WINDOW, retryLater.getRetryAfter());

    // the very next entry, of a workflow the same BPMS reports, on the same thread
    dispatchCorrelation(service, FINDABLE);

    final var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);
    assertEquals(List.of(FINDABLE), adapter.correlated);
    assertTrue(
        elapsed.compareTo(WINDOW.dividedBy(2)) < 0,
        "dispatching both entries took %s, which is the window of the workflow nobody can find yet - "
            .formatted(elapsed)
            + "the thread waited instead of giving the entry back");

  }

  @Test
  @DisplayName("A workflow which never becomes visible is blocked after block-after-attempts windows")
  public void aWorkflowWhichNeverBecomesVisibleIsBlocked() {

    final var adapter = new LaggingAdapter();
    final var service = serviceKnowingBothWorkflows(adapter);

    final var attemptsAllowed = io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties
        .builder()
        .build()
        .getBlockAfterAttempts();

    // every repetition asks for the same window and never for a longer one, so what
    // the entry costs before the store blocks it is the attempts times that window
    for (var attempt = 1; attempt <= attemptsAllowed; attempt++) {
      final var retryLater = assertThrows(
          PhaseTwoRetryLater.class,
          () -> dispatchCorrelation(service, NOT_VISIBLE_YET));
      assertEquals(WINDOW, retryLater.getRetryAfter(), "attempt "
          + attempt
          + " asked for another due time");
    }

    // said in numbers, for the defaults and a Camunda 8 window: fifty attempts of ten
    // seconds, so an entry nobody can dispatch is blocked after eight and a half
    // minutes. The attempt budget grew with the backoff of story 195, and this case
    // grew with it - what it costs is a workflow which never becomes visible being
    // asked about longer, which is cheap: the window is the adapter's and stays short,
    // it is the growing backoff which is NOT applied here
    assertEquals(
        Duration.ofSeconds(500),
        Duration.ofSeconds(10).multipliedBy(attemptsAllowed));

  }

}
