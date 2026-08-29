package io.vanillabp.migration.test;

import java.util.HashMap;
import java.util.Map;

import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.spi.PhaseOperation;

/**
 * The phase operations of an adapter double which is not the subject of the test.
 * <p>
 * Most tests here care about one operation, or about none at all - they watch the
 * election, the outbox or the delivery log. Their adapter still has to answer what it
 * serves, so it answers everything and does nothing, and a test which cares about one
 * operation puts its own handler into that map.
 */
public final class TestPhaseOperations {

  private TestPhaseOperations() {
  }

  /**
   * @param <A> The workflow-aggregate type
   * @return A handler per core operation, each of them doing nothing in both phases
   */
  public static <A> Map<PhaseOperation, PhaseOperationHandler<A>> doingNothing() {

    final var operations = new HashMap<PhaseOperation, PhaseOperationHandler<A>>();
    PhaseOperation.CORE_OPERATIONS
        .forEach(
            operation -> operations
                .put(
                    operation,
                    PhaseOperationHandler
                        .of(
                            request -> {
                            },
                            request -> {
                            })));
    return operations;

  }

  /**
   * @param <A> The workflow-aggregate type
   * @param operation The operation whose phase two fails
   * @param failure What it throws
   * @return Handlers for every core operation, the given one failing after the commit
   */
  public static <A> Map<PhaseOperation, PhaseOperationHandler<A>> failingInPhaseTwo(
      final PhaseOperation operation,
      final RuntimeException failure) {

    return with(
        operation,
        PhaseOperationHandler
            .of(
                request -> {
                },
                request -> {
                  throw failure;
                }));

  }

  /**
   * @param <A> The workflow-aggregate type
   * @param operation The operation this test cares about
   * @param handler What it does
   * @return Handlers for every core operation, the given one among them
   */
  public static <A> Map<PhaseOperation, PhaseOperationHandler<A>> with(
      final PhaseOperation operation,
      final PhaseOperationHandler<A> handler) {

    final var operations = TestPhaseOperations.<A>doingNothing();
    operations.put(operation, handler);
    return operations;

  }

}
