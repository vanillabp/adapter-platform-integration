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

  /**
   * Where the bytes of a call which carries a payload are read from, and where they are
   * removed once the call was dispatched. <code>null</code> for a caller which brings
   * none - an entry which names a payload is then dispatched without it, which the
   * router's handler sees as a call carrying nothing.
   */
  private final io.vanillabp.integration.spi.PhaseTwoPayloadStore payloadStore;

  public GruelboxPhaseTwoDispatchBean(
      final PhaseTwoRouter phaseTwoRouter) {

    this(phaseTwoRouter, null);

  }

  /**
   * @param phaseTwoRouter The router the rebuilt call is handed to
   * @param payloadStore Where the payload of an entry which names one is read from
   */
  public GruelboxPhaseTwoDispatchBean(
      final PhaseTwoRouter phaseTwoRouter,
      final io.vanillabp.integration.spi.PhaseTwoPayloadStore payloadStore) {

    this.phaseTwoRouter = phaseTwoRouter;
    this.payloadStore = payloadStore;

  }

  @Override
  public void dispatch(
      final String operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId,
      final String serializedArgs) {

    final var args = PhaseTwoCall.deserializeArgs(serializedArgs);
    // the one extra read this form costs, and only for an entry which names a payload:
    // a lookup by primary key, once per dispatch attempt
    final var payloadReference = args.get(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
    final var payload = (payloadReference == null) || (payloadStore == null)
        ? null
        : payloadStore.read(payloadReference);
    final var call = PhaseTwoCall
        .forDispatch(
            operation,
            workflowModuleId,
            bpmnProcessId,
            workflowAggregateId,
            adapterId,
            args,
            payload);
    // set by the submitter wrapper on this thread - a retried entry runs the START
    // re-dispatch mitigation
    final var previouslyAttempted = GruelboxRedispatchAwareSubmitter.isPreviouslyAttempted();

    phaseTwoRouter.dispatch(call, previouslyAttempted);

    // the bytes have done their work, and this runs in the transaction gruelbox opened
    // around the invocation - the same one it marks the entry processed in, so the two
    // end together. Nothing is removed where the dispatch threw: the entry comes again
    if ((payloadReference != null) && (payloadStore != null)) {
      payloadStore.remove(payloadReference);
    }

  }

}
