package io.vanillabp.integration.spi;

/**
 * Transaction outbox used to reliably execute the second phase of two-phase committed
 * BPMS calls after the local transaction which persisted the workflow aggregate was
 * committed.
 * <p>
 * A store implements exactly one method, {@link #schedule(PhaseTwoCall)}, and never
 * learns which operations exist: a call carries the operation's NAME, its arguments and
 * its idempotency key, all of them opaque here. The core builds those calls from the
 * {@link PhaseOperation} the application asked for, and dispatch happens through the
 * core's <code>PhaseTwoRouter</code> which routes the call to the
 * <code>MigrationProcessService</code> of the workflow module/BPMN process.
 * <p>
 * An EXTENSION uses the same outbox for after-commit work of its own: it registers
 * an operation in the {@link PhaseOperationRegistry}, builds its calls with
 * {@link PhaseTwoCall#of(PhaseOperation, String, String, String, String, java.util.Map)}
 * and schedules them here - the store treats them like any other entry, and the
 * router dispatches them to the extension's own handler.
 * <p>
 * Implementations are provided by the platform integrations (e.g. based on JDBC, JPA
 * or MongoDB) or by the business application itself, since the platform-neutral core
 * must not depend on any particular persistence technology.
 * <p>
 * <strong>Scheduling contract:</strong> {@link #schedule(PhaseTwoCall)} MUST be
 * invoked within the still-running local transaction that persists the workflow
 * aggregate, and the implementation MUST enlist the outbox entry in exactly that
 * transaction: the entry becomes visible if and only if the transaction commits. This
 * guarantees atomicity of "aggregate persisted" and "phase two will run" - preventing
 * ghost workflows in the BPMS as well as aggregates without workflows.
 * <p>
 * <strong>Idempotency contract:</strong> Implementations MUST enforce uniqueness of
 * {@link PhaseTwoCall#idempotencyKey()} (where present) AMONG THE ENTRIES STILL
 * WAITING FOR THEIR DISPATCH, using the store's unique-constraint mechanism (unique
 * index/constraint on the persisted key). A key says "this operation is planned once",
 * not "this operation ever happened": scheduling a call whose key matches an entry
 * which has not been dispatched yet is a no-op returning <code>false</code>, while the
 * same call after that entry reached the BPMS is a NEW operation and is scheduled. That
 * is what makes a second round of a loop, or a second element of a multi-instance
 * activity, correlate the same message again instead of waiting forever for a message
 * VanillaBP silently dropped - see decision 22 in the repository's DECISIONS.md and
 * {@link #schedule(PhaseTwoCall)} for what a store logs about a discard.
 * <p>
 * What the narrowed window does NOT protect against is two entries planned in the same
 * batch of work: multi-instance siblings of one aggregate share module, process and
 * aggregate ID, and only their correlation id is left to tell them apart. Where it is
 * the same, one of them is discarded and the store says so - the caller has to vary
 * the correlation id per element.
 * <p>
 * <strong>Recovery contract:</strong> Implementations must dispatch every
 * committed-but-unprocessed entry
 * <ul>
 * <li>right after the local transaction was committed and</li>
 * <li>after an application restart (crash recovery), by polling for left-over
 * entries.</li>
 * </ul>
 * On failed dispatch the entry has to be retried with a backoff.
 * <p>
 * <strong>DONE instead of delete:</strong> A successful dispatch marks the entry as
 * DONE - it is NOT deleted immediately. Physical deletion happens asynchronously
 * after a configurable retention period (<code>vanillabp.outbox.*</code>, default 7
 * days). What that retention buys is a dispatched entry somebody can still look at
 * during support; it does NOT extend the deduplication window, which ends with the
 * dispatch. A store therefore has to take the key of a dispatched entry out of
 * whatever enforces uniqueness, and say in its own javadoc how it does that.
 * <p>
 * <strong>At-least-once residual window:</strong> A crash between the remote BPMS
 * call and marking the entry DONE re-dispatches the entry on recovery. Narrowing the
 * deduplication window does not widen this one: a redispatch reads the very entry
 * which is still not DONE, so it is the store's own attempt bookkeeping - gruelbox' or
 * the STATUS/ATTEMPTS columns of the stores VanillaBP wrote itself - which carries the
 * guarantee, never the idempotency key. This residual window is accepted (eventual
 * consistency); adapters keep their operations idempotent, which the adapter SPI's
 * {@code PhaseOperationHandler} demands of its phase two. The window is MINIMIZED
 * (not closed) for START operations: a store passing
 * &quot;this entry was dispatched before&quot; to the router's dispatch method
 * triggers a probe of the recorded adapter's
 * {@code MigratableProcessService#awarenessOfWorkflowForRedispatch} - a workflow
 * already known consumes the entry without a second start.
 * <p>
 * <strong>Poison entries:</strong> Entries failing repeatedly are blocked after a
 * configurable number of attempts and left in the store as a monitorable trail - the
 * implementation logs an ERROR naming workflow module, BPMN process, aggregate ID and
 * operation. Dispatch failures caused by a BPMN process no longer being part of the
 * application, or by an adapter ID no longer being configured (stale entry after a
 * configuration change), yield guiding messages naming that case.
 * <p>
 * Why a progressing operation is planned here instead of being executed in the caller's
 * transaction, and why an operation which nothing can deduplicate carries no idempotency key, is
 * decision 2 in the repository's DECISIONS.md.
 */
public interface PhaseTwoOutbox {

  /**
   * Schedule the given phase-two call. MUST be invoked within the still-running
   * local transaction that persists the workflow aggregate, and MUST enlist in that
   * transaction (entry becomes visible if and only if the transaction commits).
   * <p>
   * The return value is not decoration: a <code>false</code> means an operation the
   * application asked for will not happen. The store logs it, naming both causes it
   * cannot tell apart - a redelivered at-least-once dispatch, or a genuinely second
   * operation which lost against one still waiting - and the core reports it to
   * whoever called.
   *
   * @param call The phase-two call to schedule
   * @return <code>true</code> if the call was scheduled, <code>false</code> if an
   *         entry with the same {@link PhaseTwoCall#idempotencyKey()} is still
   *         waiting for its dispatch (no-op)
   */
  boolean schedule(
      PhaseTwoCall call);

  /**
   * The adapter ids the entries of one workflow's BPMN process are waiting for - every
   * entry which is not DONE yet and names an adapter
   * ({@link PhaseTwoCall#adapterId()}, set for the START operations; the probing
   * operations carry none and are not part of any answer).
   * <p>
   * Asked once at startup, and only for one question: an adapter id which entries are
   * waiting for although it is not configured any more means that the id was RENAMED or
   * was removed too early. Both end the same way, and today only the dispatch says so -
   * the entry fails, is repeated and finally blocked, while the workflow it would have
   * started was persisted long ago.
   * <p>
   * The default answers an empty set, which means "this store cannot say": the check is
   * then skipped rather than invented. A store implementing it answers a cheap query;
   * it is called once per BPMN process at startup and never at runtime.
   *
   * @param workflowModuleId The workflow module to ask about
   * @param bpmnProcessId The BPMN process to ask about
   * @return The adapter ids of entries waiting, empty if the store cannot say
   */
  default java.util.Set<String> adapterIdsOfPendingCalls(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return java.util.Set.of();

  }

  /**
   * How many entries are waiting to be dispatched right now. It is the
   * number an operator looks at first when a BPMS is unreachable: phase two is where
   * a broken connection piles up, and a rising figure says the application is fine
   * while the BPMS is not.
   * <p>
   * The default is {@link java.util.OptionalLong#empty()}, which means "this store
   * cannot say" - no meter is published then, which is honest, whereas a zero would
   * be a claim. A store implementing it answers with a cheap query; it is called
   * whenever the metrics backend collects, so an expensive scan does not belong here.
   *
   * @return The number of entries waiting, or empty if the store cannot count them
   */
  default java.util.OptionalLong pendingCalls() {

    return java.util.OptionalLong.empty();

  }

}
