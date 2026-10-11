package io.vanillabp.integration.spi;

/**
 * What a {@link PhaseOperation} does once the local transaction has committed and the
 * outbox store hands its entry back for execution. It is registered together with the
 * operation in the {@link PhaseOperationRegistry}. The core registers the dispatch of its
 * own operations, which routes into the process service of the workflow module and BPMN
 * process of the call. An extension registers the dispatch of the operations it
 * contributes.
 * <p>
 * The call is delivered exactly as it was scheduled (the store never interprets it). An
 * exception stops the dispatch. The outbox store keeps the entry, retries it with a
 * backoff, and blocks it after too many attempts. So throwing is the way to say
 * &quot;not now&quot;, never a way to say &quot;never&quot;.
 */
@FunctionalInterface
public interface PhaseOperationDispatch {

  /**
   * Executes the given call.
   *
   * @param call The phase-two call as it was scheduled
   * @param previouslyAttempted Whether the outbox entry was dispatched before (a
   *        recovered or retried entry). Core start operations use it to make a second
   *        dispatch less risky. An extension may use it to tell the first attempt from a
   *        repetition, but it must stay correct either way. Stores which cannot tell pass
   *        <code>false</code>
   */
  void dispatch(
      PhaseTwoCall call,
      boolean previouslyAttempted);

}
