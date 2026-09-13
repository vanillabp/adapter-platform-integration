package io.vanillabp.integration.outbox.gruelbox;

import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;

/**
 * What gruelbox calls once the scheduling transaction committed: it rebuilds the
 * {@link PhaseTwoCall} and routes it through the core's {@link PhaseTwoRouter}.
 * <p>
 * One attempt per call, and a failure travels on. That includes the failure of a workflow
 * the BPMS has not made searchable yet ({@link PhaseTwoRetryLater}, on Camunda 8 the
 * ordinary case while its exporter catches up): the entry goes back to the store and is
 * dispatched again, instead of being repeated here.
 * <p>
 * Repeating it here is what this class used to do, in slices of half a second, and the
 * transaction is the reason it cannot: gruelbox opened one around this call and the
 * dispatch JOINED it (<code>TransactionRunner#requireTransaction</code>, which is what
 * keeps the entry and everything the application wrote in one unit of work). A rejected
 * attempt rolls that transaction back, and Spring marks a transaction somebody joined as
 * rollback-only for good - there is no way back from it. So a second attempt inside it
 * reached the BPMS and then lost its commit. The report was out while the entry stayed open,
 * and what a handler had written while reporting went back with the transaction. The entry
 * came again afterwards, so the consumer got the same report twice, and gruelbox said what it
 * could see (<code>Failed to update attempt count</code>): the row it wanted to write had
 * been counted up by the update which rolled back. See
 * <code>ARejectedDispatchIsPlannedAgainTest</code>.
 * <p>
 * What the entry costs instead is one due time. Gruelbox has one distance for the whole
 * outbox, so the window the adapter named is written onto the row by
 * {@link GruelboxPhaseTwoFailureListener} once the failed attempt is committed - the same
 * way a permanent failure is blocked there. The attempt is counted like any other, and
 * <code>vanillabp.outbox.block-after-attempts</code> of them still block the entry, which
 * is what ends a workflow which never becomes visible.
 * <p>
 * Why the attempt ends here instead of being repeated is decision 49 in the repository's
 * DECISIONS.md.
 */
public class GruelboxPhaseTwoDispatchBean implements GruelboxPhaseTwoDispatch {

  private final PhaseTwoRouter phaseTwoRouter;

  public GruelboxPhaseTwoDispatchBean(
      final PhaseTwoRouter phaseTwoRouter) {

    this.phaseTwoRouter = phaseTwoRouter;

  }

  @Override
  public void dispatch(
      final String operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId,
      final String serializedArgs) {

    final var call = PhaseTwoCall
        .forDispatch(
            operation,
            workflowModuleId,
            bpmnProcessId,
            workflowAggregateId,
            adapterId,
            PhaseTwoCall.deserializeArgs(serializedArgs));
    // set by the submitter wrapper on this thread - a retried entry runs the START
    // re-dispatch mitigation
    final var previouslyAttempted = GruelboxRedispatchAwareSubmitter.isPreviouslyAttempted();

    phaseTwoRouter.dispatch(call, previouslyAttempted);

  }

}
