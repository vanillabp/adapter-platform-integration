package io.vanillabp.integration.adapter.spi;

import java.util.function.Consumer;

import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoOutbox;

/**
 * What ONE adapter does for ONE {@link PhaseOperation}, in both of its phases. An
 * adapter contributes a handler for each operation it can serve
 * ({@link MigratableProcessService#phaseOperations()}). That map is all it has to write
 * about outgoing work. Which BPMS runs an operation, which probe finds it and how a
 * failure is worded belong to the operation and to the core.
 * <p>
 * The two methods are the two halves of decision 3 in the repository's DECISIONS.md,
 * "phase one asks, phase two acts". The split is the same for every adapter and every
 * operation, an embedded engine included.
 *
 * @param <A> The workflow-aggregate type
 */
public interface PhaseOperationHandler<A> {

  /**
   * Runs inside the caller's transaction, right before it is committed, and only ASKS.
   * Does the parked task still exist? Is a subscription waiting for this message? Is
   * such a message declared in a deployed model? Would this workflow collide with one
   * which already runs? If the BPMS offers a lock for what phase two will do, taking it
   * here counts as asking too.
   * <p>
   * It MUST NOT move the BPMN process forward, whether the BPMS is remote or embedded.
   * An engine command which loses a concurrency conflict cannot be repeated inside the
   * caller's transaction, because the conflict marks that transaction rollback-only.
   * Throwing fails the caller's transaction, and that is what this phase is for. The
   * stack trace still points at the business code which made the call.
   * <p>
   * An adapter answers as exactly as its BPMS allows. Its own README says where it stays
   * silent. Doing nothing here is a valid answer.
   *
   * @param request What the operation is about, see {@link PhaseOneRequest}
   */
  void phaseOne(
      PhaseOneRequest<A> request);

  /**
   * Runs after the caller's transaction was committed. The {@link PhaseTwoOutbox} sends
   * it on a thread of its own, and this is where the BPMS is changed. After a crash it
   * runs again on the next start of the application.
   * <p>
   * <strong>Idempotency contract:</strong> the outbox sends each entry at least once. So
   * this call may repeat an attempt which already succeeded. The operation documents
   * what that means for it ({@link PhaseOperation}). The rule everywhere: a repetition
   * must not cause a second effect. And if something is already gone, such as a
   * completed task or an ended workflow, that counts as success. Log it and return
   * normally. Throw only for failures of the infrastructure, which the outbox retries.
   * For a failure which no repetition can fix,
   * {@link MigratableProcessService#isPhaseTwoFailureRepeatable(Throwable)} says
   * <code>false</code>, and the entry is blocked instead.
   * <p>
   * There is a third answer between those two, and an eventually consistent BPMS needs
   * it. Throw {@link io.vanillabp.integration.spi.PhaseTwoRetryLater} with the window
   * the BPMS needs. On every store VanillaBP ships, the entry then comes back after
   * exactly that window. Never sleep here instead. The thread which runs this belongs to
   * the store, so waiting on it holds back the operations of every other workflow.
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
