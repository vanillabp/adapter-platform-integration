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
 * Stands in for an extension which passes the state it saw at its sync point, the way a
 * sync to the Business Cockpit does. It registers an operation of its own and records
 * the calls dispatched to it, payload included.
 */
@ApplicationScoped
public class PayloadExtension {

  public static final String OPERATION_NAME = "payload-extension:NOTIFY";

  public static final String ARG_EVENT = "event";

  public static final PhaseOperation OPERATION = PhaseOperation
      .extensionOperation(OPERATION_NAME)
      .idempotencyKey(
          call -> Optional
              .of(
                  "%s|%s|%s".formatted(
                      call.workflowModuleId(),
                      call.workflowAggregateId(),
                      call.args().get(ARG_EVENT))))
      .describedAs(args -> "notifying about event '%s'".formatted(args.get(ARG_EVENT)))
      .build();

  @Inject
  PhaseOperationRegistry registry;

  private final List<PhaseTwoCall> dispatched = new CopyOnWriteArrayList<>();

  void registerOperation(
      @Observes final StartupEvent event) {

    registry
        .register(
            OPERATION,
            (
                call,
                previouslyAttempted) -> dispatched.add(call));

  }

  /**
   * Builds a call of this extension's operation carrying the given state.
   *
   * @param workflowAggregateId The aggregate's ID in serialized form
   * @param event The event to be published
   * @param payload The bytes to carry, or <code>null</code>
   * @return The call to be scheduled
   */
  public static PhaseTwoCall call(
      final String workflowAggregateId,
      final String event,
      final byte[] payload) {

    return PhaseTwoCall
        .of(
            OPERATION, "test-module", "dummy", workflowAggregateId, null, Map.of(ARG_EVENT, event), payload);

  }

  /**
   * Forgets what was dispatched before. A test which waits for the first call has to
   * start from nothing, or it reads the call of the test which ran before it.
   */
  public void reset() {

    dispatched.clear();

  }

  /**
   * What was dispatched up to now, without waiting for anything.
   *
   * @return The dispatched calls
   */
  public List<PhaseTwoCall> dispatched() {

    return List.copyOf(dispatched);

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
