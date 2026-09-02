package io.vanillabp.integration.adapter.spi;

import java.util.function.Consumer;

import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoOutbox;

/**
 * What ONE adapter does for ONE {@link PhaseOperation}, in both of its phases. An
 * adapter contributes a handler per operation it can serve
 * ({@link MigratableProcessService#phaseOperations()}), and that map is the whole of
 * what it has to write about outbound work: which BPMS executes an operation, which
 * probe finds it and how a failure is worded belongs to the operation and to the core.
 * <p>
 * The two methods are the two halves of decision 3 in the repository's DECISIONS.md,
 * "phase one asks, phase two acts", and the split is the same for every adapter and
 * every operation - an embedded engine included.
 *
 * @param <A> The workflow-aggregate type
 */
public interface PhaseOperationHandler<A> {

  /**
   * Runs inside the caller's transaction, immediately before it is committed, and only
   * ASKS: does the parked task still exist, is a subscription waiting for this
   * message, is such a message declared in a deployed model, would this workflow
   * collide with one which already runs. Where the BPMS offers a lock for what phase
   * two will do, taking it here is asking as well.
   * <p>
   * It MUST NOT advance the BPMN process, no matter whether the BPMS is remote or
   * embedded: an engine command which loses a concurrency conflict cannot be repeated
   * inside the caller's transaction, because the conflict leaves that transaction
   * rollback-only. Throwing fails the caller's transaction, which is what this phase is
   * for - the stack trace still points at the business code which made the call.
   * <p>
   * An adapter answers as exactly as its BPMS allows and says in its own README where
   * it stays silent. Doing nothing here is a legitimate answer.
   *
   * @param request What the operation is about, see {@link PhaseOneRequest}
   */
  void phaseOne(
      PhaseOneRequest<A> request);

  /**
   * Runs after the caller's transaction was committed, dispatched from the
   * {@link PhaseTwoOutbox} on a thread of its own, and this is where the BPMS is
   * changed. After a crash it runs again on the next start of the application.
   * <p>
   * <strong>Idempotency contract:</strong> the outbox dispatches at-least-once, so this
   * may be a repetition of an attempt which already succeeded. What that means per
   * operation is documented with the operation ({@link PhaseOperation}); the rule
   * throughout is that a repetition must not produce a second effect and that something
   * which is already gone - a completed task, an ended workflow - is success, logged and
   * returned from normally. Throwing is reserved for infrastructure failures, where the
   * outbox retries; a failure repeating cannot fix is what
   * {@link MigratableProcessService#isPhaseTwoFailureRepeatable(Throwable)} says
   * <code>false</code> about, and it blocks the entry instead.
   *
   * @param request What the operation is about, see {@link PhaseTwoRequest}
   */
  void phaseTwo(
      PhaseTwoRequest<A> request);

  /**
   * Builds a handler from the two phases as lambdas.
   *
   * @param <A> The workflow-aggregate type
   * @param phaseOne What to ask inside the caller's transaction
   * @param phaseTwo What to do after the commit
   * @return The handler
   */
  static <A> PhaseOperationHandler<A> of(
      final Consumer<PhaseOneRequest<A>> phaseOne,
      final Consumer<PhaseTwoRequest<A>> phaseTwo) {

    return new PhaseOperationHandler<A>() {

      @Override
      public void phaseOne(
          final PhaseOneRequest<A> request) {

        phaseOne.accept(request);

      }

      @Override
      public void phaseTwo(
          final PhaseTwoRequest<A> request) {

        phaseTwo.accept(request);

      }

    };

  }

}
