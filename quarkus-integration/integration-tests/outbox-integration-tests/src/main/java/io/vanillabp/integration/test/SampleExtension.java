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
 * Stands in for a real VanillaBP extension (e.g. the Business Cockpit) using the
 * outbox for its own crash-safe after-commit work: it registers an operation of its
 * own in the {@link PhaseOperationRegistry} at startup and records the calls
 * dispatched to it.
 * <p>
 * Everything an extension needs is used here: a namespaced operation name, an
 * idempotency key of its own making, arguments travelling with the call and a
 * dispatch which is NOT routed through the aggregate-to-adapter election of the
 * core operations.
 */
@ApplicationScoped
public class SampleExtension {

  public static final String OPERATION_NAME = "sample-extension:NOTIFY";

  public static final String ARG_EVENT = "event";

  /**
   * The operation contributed by this extension: deduplicated per workflow
   * aggregate AND event, so the same event is published at most once while
   * different events of the same workflow are all published.
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

  /**
   * How the dispatch loads the workflow aggregate of a call, set by a test which is
   * about the state such a load sees. It takes the aggregate's id and the auditing id of
   * the call, which is what an extension reporting an event has at that moment.
   */
  private volatile java.util.function.BiFunction<String, String, Object> aggregateLoader;

  /**
   * What those loads answered, in the order they happened.
   */
  private final List<Object> loadedWhileDispatching = new CopyOnWriteArrayList<>();

  private volatile int failNextDispatches;

  void onStart(
      @Observes final StartupEvent event) {

    registry
        .register(
            OPERATION,
            (
                call,
                previouslyAttempted) -> {
              if (failNextDispatches > 0) {
                failNextDispatches--;
                throw new RuntimeException("test dispatch failure");
              }
              if (aggregateLoader != null) {
                loadedWhileDispatching
                    .add(aggregateLoader.apply(call.workflowAggregateId(), call.auditingId()));
              }
              dispatched.add(call);
            });

  }

  /**
   * Builds a call of this extension's operation - what the extension would
   * schedule inside the business transaction.
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
   * Builds a call of this extension's operation which carries the state the extension
   * saw when it planned the call - what a sync to the Business Cockpit passes.
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

  /**
   * Builds a call of this extension's operation which is to see the aggregate as it is
   * now rather than as it will be when the entry is dispatched - what a sync to the
   * Business Cockpit asks for.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param workflowAggregateId The aggregate's ID in serialized form
   * @param event The event to be published
   * @param auditingId The state to be seen at the dispatch, or <code>null</code>
   * @return The call to be scheduled
   */
  public static PhaseTwoCall callAboutTheStateOfNow(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String event,
      final String auditingId) {

    return call(workflowModuleId, bpmnProcessId, workflowAggregateId, event)
        .askingForTheStateOfTheEvent(auditingId);

  }

  public List<PhaseTwoCall> getDispatched() {

    return dispatched;

  }

  /**
   * Lets the dispatch load the workflow aggregate of every call it gets from now on.
   *
   * @param aggregateLoader Takes the aggregate's id and the call's auditing id
   */
  public void loadTheAggregateWhileDispatching(
      final java.util.function.BiFunction<String, String, Object> aggregateLoader) {

    this.aggregateLoader = aggregateLoader;

  }

  /**
   * @return What the dispatches loaded, in the order they ran
   */
  public List<Object> getLoadedWhileDispatching() {

    return loadedWhileDispatching;

  }

  public void reset() {

    dispatched.clear();
    loadedWhileDispatching.clear();
    aggregateLoader = null;
    failNextDispatches = 0;

  }

  public void failNextDispatches(
      final int count) {

    failNextDispatches = count;

  }

  /**
   * Waits until the given number of calls was dispatched.
   * <p>
   * The handler runs inside the dispatch, so on return the entry of the last call may
   * still be waiting to be marked DONE and its key still deduplicates. A test which
   * schedules that key again has to wait for the ENTRY, in the store.
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
