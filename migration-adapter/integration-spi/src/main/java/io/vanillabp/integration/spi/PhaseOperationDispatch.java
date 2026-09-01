package io.vanillabp.integration.spi;

/**
 * What a {@link PhaseOperation} does once the local transaction was committed
 * and the outbox store hands its entry back for execution. Registered together
 * with the operation in the {@link PhaseOperationRegistry}: the core registers
 * the dispatch of its own operations (routing into the process service of the
 * call's workflow module and BPMN process), an extension registers the dispatch
 * of the operations it contributes.
 * <p>
 * The call is delivered exactly as it was scheduled (the store never interprets
 * it). An exception aborts the dispatch: the outbox store keeps the entry, retries
 * it with a backoff and blocks it after too many attempts - so throwing is the way
 * to say &quot;not now&quot;, never a way to say &quot;never&quot;.
 */
@FunctionalInterface
public interface PhaseOperationDispatch {

  /**
   * Execute the given call.
   *
   * @param call The phase-two call as it was scheduled
   * @param previouslyAttempted Whether the outbox entry was dispatched before (a
   *        recovered or retried entry). Core start operations use it for the
   *        re-dispatch mitigation; an extension may use it to distinguish the
   *        first attempt from a repetition, but must stay correct either way -
   *        stores which cannot tell pass <code>false</code>
   */
  void dispatch(
      PhaseTwoCall call,
      boolean previouslyAttempted);

}
