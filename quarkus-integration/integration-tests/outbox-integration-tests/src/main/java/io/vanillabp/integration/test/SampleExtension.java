package io.vanillabp.integration.test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import io.quarkus.runtime.StartupEvent;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseOperationRegistry;
import io.vanillabp.integration.spi.PhaseTwoCall;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Stands in for a real VanillaBP extension, e.g. the Business Cockpit. Such an extension
 * uses the outbox for its own work after the commit, so the work survives a crash. At
 * startup this class registers an operation of its own in the
 * {@link PhaseOperationRegistry}. It records the calls dispatched to it.
 * <p>
 * It uses everything an extension needs: an operation name with a namespace, an
 * idempotency key of its own making, and arguments which travel with the call. Its
 * dispatch does NOT go through the election which maps an aggregate to an adapter for the
 * core operations.
 */
@ApplicationScoped
public class SampleExtension {

  public static final String OPERATION_NAME = "sample-extension:NOTIFY";

  public static final String ARG_EVENT = "event";

  /**
   * The operation which this extension contributes. It is deduplicated per workflow
   * aggregate AND event. So the same event is published at most once, but different events
   * of the same workflow are all published.
   */
  public static final PhaseOperation OPERATION = PhaseOperation
      .extensionOperation(OPERATION_NAME)
      .idempotencyKey(
          call -> Optional
              .of(
                  "%s|%s|%s|%s".formatted(
                      call.workflowModuleId(),
                      call.bpmnProcessId(),
                      call.workflowAggregateId(),
                      call.args().get(ARG_EVENT))))
      .describedAs(args -> "notifying about event '%s'".formatted(args.get(ARG_EVENT)))
      .build();

  @Inject
  PhaseOperationRegistry registry;

  private final List<PhaseTwoCall> dispatched = new CopyOnWriteArrayList<>();

  private volatile int failNextDispatches;
  /**
   * Counts down when a held dispatch entered the handler. So a test knows that the entry
   * is claimed.
   */
  private volatile java.util.concurrent.CountDownLatch dispatchEntered;

  /**
   * What a held dispatch waits for. A test may plan against an entry which a dispatch has
   * ALREADY taken. That dispatch has to stand still while the test plans. There is no other
   * way to be sure that the claim happened.
   */
  private volatile java.util.concurrent.CountDownLatch releaseDispatch;

  /**
   * Whether a dispatch is still to be held. Only the FIRST dispatch after
   * {@link #holdNextDispatch()} waits. This flag takes the hold away from the ones which
   * follow.
   */
  private final java.util.concurrent.atomic.AtomicBoolean holdArmed = new java.util.concurrent.atomic.AtomicBoolean();

  void onStart(
      @Observes final StartupEvent event) {

    registry
        .register(
            OPERATION,
            (
                call,
                previouslyAttempted) -> {
              holdWhereATestAskedForIt();
              if (failNextDispatches > 0) {
                failNextDispatches--;
                throw new RuntimeException("test dispatch failure");
              }
              dispatched.add(call);
            });

  }

  /**
   * Builds a call of this extension's operation. The extension would schedule it inside
   * the business transaction.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateId The aggregate's ID in serialized form
   * @param event The event to be published
   * @return The call to be scheduled
   */
  public static PhaseTwoCall call(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String event) {

    return call(workflowModuleId, bpmnProcessId, workflowAggregateId, event, null);

  }

  /**
   * Builds a call of this extension's operation which carries the state the extension saw
   * when it planned the call. A sync to the Business Cockpit passes such a state.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateId The aggregate's ID in serialized form
   * @param event The event to be published
   * @param payload The bytes to carry, or <code>null</code>
   * @return The call to be scheduled
   */
  public static PhaseTwoCall call(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String event,
      final byte[] payload) {

    return PhaseTwoCall
        .of(
            OPERATION, workflowModuleId, bpmnProcessId, workflowAggregateId, null, Map
                .of(ARG_EVENT, event),
            payload);

  }

  public List<PhaseTwoCall> getDispatched() {

    return dispatched;

  }

  public void reset() {

    dispatched.clear();
    failNextDispatches = 0;
    holdArmed.set(false);

  }

  /**
   * Makes the next dispatch stop inside the handler until
   * {@link #releaseHeldDispatch()} lets it go. By then the entry is claimed, and a test
   * about a claimed entry needs exactly that state.
   */
  public void holdNextDispatch() {

    dispatchEntered = new java.util.concurrent.CountDownLatch(1);
    releaseDispatch = new java.util.concurrent.CountDownLatch(1);
    holdArmed.set(true);

  }

  /**
   * Waits until the held dispatch entered the handler.
   *
   * @param timeoutMillis The maximum time to wait
   * @throws InterruptedException If interrupted while waiting
   */
  public void awaitHeldDispatchEntered(
      final long timeoutMillis) throws InterruptedException {

    if (!dispatchEntered.await(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
      throw new AssertionError("no dispatch entered the handler");
    }

  }

  /**
   * Lets the held dispatch finish.
   */
  public void releaseHeldDispatch() {

    holdArmed.set(false);
    final var release = releaseDispatch;
    if (release != null) {
      release.countDown();
    }

  }

  /**
   * Stops the dispatch which entered first, where a test asked for it.
   */
  private void holdWhereATestAskedForIt() {

    if (!holdArmed.compareAndSet(true, false)) {
      return;
    }
    final var release = releaseDispatch;
    dispatchEntered.countDown();
    try {
      release.await(30, java.util.concurrent.TimeUnit.SECONDS);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

  }

  public void failNextDispatches(
      final int count) {

    failNextDispatches = count;

  }

  /**
   * Waits until the given number of calls was dispatched.
   * <p>
   * The handler runs inside the dispatch. So on return the entry of the last call may still
   * wait to be marked DONE, and its key still deduplicates. A test which schedules that key
   * again has to wait for the ENTRY, in the store.
   *
   * @param count The number of calls awaited
   * @param timeoutMillis The maximum time to wait
   * @return The dispatched calls
   * @throws InterruptedException If interrupted while waiting
   */
  public List<PhaseTwoCall> awaitDispatched(
      final int count,
      final long timeoutMillis) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + timeoutMillis;
    while (dispatched.size() < count) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(
            "Only %d of %d expected extension-operation dispatches happened".formatted(
                dispatched.size(),
                count));
      }
      Thread.sleep(50);
    }
    return List.copyOf(dispatched);

  }

}
