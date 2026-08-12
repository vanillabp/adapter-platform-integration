package io.vanillabp.integration.test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import io.quarkus.runtime.StartupEvent;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOperation;
import io.vanillabp.integration.spi.PhaseTwoOperationRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Stands in for a real VanillaBP extension (e.g. the Business Cockpit) using the
 * outbox for its own crash-safe after-commit work: it registers an operation of its
 * own in the {@link PhaseTwoOperationRegistry} at startup and records the calls
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
  public static final PhaseTwoOperation OPERATION = PhaseTwoOperation
      .extensionOperation(
          OPERATION_NAME,
          call -> Optional
              .of(
                  "%s|%s|%s|%s".formatted(
                      call.workflowModuleId(),
                      call.bpmnProcessId(),
                      call.workflowAggregateId(),
                      call.args().get(ARG_EVENT))));

  @Inject
  PhaseTwoOperationRegistry registry;

  private final List<PhaseTwoCall> dispatched = new CopyOnWriteArrayList<>();

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

    return PhaseTwoCall
        .of(
            OPERATION, workflowModuleId, bpmnProcessId, workflowAggregateId, null, Map.of(ARG_EVENT, event));

  }

  public List<PhaseTwoCall> getDispatched() {

    return dispatched;

  }

  public void reset() {

    dispatched.clear();
    failNextDispatches = 0;

  }

  public void failNextDispatches(
      final int count) {

    failNextDispatches = count;

  }

  /**
   * Waits until the given number of calls was dispatched.
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
