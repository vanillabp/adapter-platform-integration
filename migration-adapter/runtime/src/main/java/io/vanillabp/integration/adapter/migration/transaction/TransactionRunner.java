package io.vanillabp.integration.adapter.migration.transaction;

import java.util.function.Supplier;

/**
 * Neutral transaction abstraction used by the core to run
 * <code>&#64;WorkflowTask</code> handlers (load aggregate - invoke - save) within a
 * transaction. Implemented per platform (Spring:
 * <code>TransactionTemplate</code>-based; Quarkus:
 * <code>QuarkusTransaction</code>-based, additionally activating the CDI request
 * context around the work because handlers are invoked on adapter threads).
 * <p>
 * Contract: a {@link RuntimeException} thrown by the work rolls the transaction
 * back and propagates to the caller; a normal return commits.
 */
public interface TransactionRunner {

  /**
   * Runs the given work in a NEW transaction, suspending a possibly active one
   * (semantics of "requires new").
   *
   * @param <T> The result type
   * @param work The work to run
   * @return The work's result
   */
  <T> T requireNew(
      Supplier<T> work);

  /**
   * Runs the given work within the transaction already active on the calling
   * thread (semantics of "mandatory": embedded BPMS like Camunda 7 invoke handlers
   * inside the engine's transaction). Implementations fail with a guiding message
   * if no transaction is active.
   *
   * @param <T> The result type
   * @param work The work to run
   * @return The work's result
   */
  <T> T inCurrent(
      Supplier<T> work);

}
