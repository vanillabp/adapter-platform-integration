package io.vanillabp.integration.spi;

import org.slf4j.LoggerFactory;

/**
 * Phase two is a MOMENT, not a kind of call: phase one is the caller's transaction, phase
 * two is everything which runs after it committed, and this outbox carries whatever was
 * planned for that moment - a call to a BPMS as much as an operation an extension
 * registered for itself.
 * <p>
 * The case VanillaBP schedules here itself is the second phase of a two-phase committed
 * BPMS call: a remote BPMS cannot take part in the local transaction which persisted the
 * workflow aggregate, so the call is split in two and this store is what makes its second
 * half reliable.
 * <p>
 * A store has to implement one method, {@link #schedule(PhaseTwoCall)}, and never
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
 * <strong>A call which carries a payload:</strong> a caller may hand bytes along with
 * the identifiers ({@link PhaseTwoCall#payload()}), and a store has to keep them until
 * the call is dispatched, in the same transaction as the entry. The stores VanillaBP
 * ships put them into a {@link PhaseTwoPayloadStore} beside the entry and persist only
 * the reference, which is what keeps an entry a row of identifiers (decision 62 in the
 * repository's DECISIONS.md). A store of an application's own is free to do it
 * differently - one which keeps the whole call in memory until the commit already
 * carries the bytes and has nothing to add. What no store may do is drop them: a
 * handler would then be told a state which is not the one the caller saw.
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
 * not "this operation ever happened": a call whose key matches an entry which has not
 * been dispatched yet meets one of the two entries below, while the same call after
 * that entry reached the BPMS is a NEW operation and is scheduled. That is what makes a
 * second round of a loop, or a second element of a multi-instance activity, correlate
 * the same message again instead of waiting forever for a message VanillaBP silently
 * dropped - see decision 22 in the repository's DECISIONS.md and
 * {@link #schedule(PhaseTwoCall)} for what a store logs about a discard.
 * <p>
 * Which of the two happens is the CALL's own word, never a setting:
 * <ul>
 * <li>A plain call is discarded, and {@link #schedule(PhaseTwoCall)} answers
 * <code>false</code>. The entry which waits stays as it is, which is right where a
 * call carries the intention and the dispatch reads the state fresh.</li>
 * <li>A call marked by {@link PhaseTwoCall#replacingWhatIsStillWaiting()} takes the
 * waiting entry's place, payload included, in the transaction it is scheduled in, and
 * {@link #scheduleReplacingWhatIsStillWaiting(PhaseTwoCall)} answers <code>true</code>.
 * The waiting reports of one workflow still collapse into one, and the one which is
 * left carries the youngest state - see decision 68 in the repository's
 * DECISIONS.md.</li>
 * </ul>
 * <strong>An entry a dispatch has already taken for itself is never replaced.</strong>
 * It runs to its end, and the younger call becomes an entry of its own, which takes no
 * part in the deduplication of that key: the key belongs to the entry which is on its
 * way. Two calls then reach the handler where one was asked for, which is the price of
 * never taking work away from a dispatch which may have reached the BPMS already.
 * <p>
 * <strong>What the dispatch reads is what was written last.</strong> Removing the
 * replaced payload, writing the younger one and pointing the entry at it belong to the
 * transaction the call was scheduled in, exactly as the entry itself does, so no reader
 * meets an entry whose payload is gone and a rollback leaves the replaced entry and its
 * payload as they were. Where a store cannot enlist at all - the MongoDB stores without
 * a replica set say so in their own javadoc - a replacement is best-effort in the way
 * an insert is there.
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
   * <p>
   * A call marked by {@link PhaseTwoCall#replacingWhatIsStillWaiting()} takes the
   * waiting entry's place here as well, because the mark travels in the call. What
   * {@link #scheduleReplacingWhatIsStillWaiting(PhaseTwoCall)} adds is the warning a
   * store which never learned replacing inherits, so reach for that one.
   *
   * @param call The phase-two call to schedule
   * @return <code>true</code> if the call was scheduled, <code>false</code> if an
   *         entry with the same {@link PhaseTwoCall#idempotencyKey()} is still
   *         waiting for its dispatch (no-op)
   */
  boolean schedule(
      PhaseTwoCall call);

  /**
   * Schedule the given phase-two call so that it takes the place of the entry of the
   * same {@link PhaseTwoCall#idempotencyKey()} which is still waiting for its dispatch,
   * payload included. Everything {@link #schedule(PhaseTwoCall)} demands holds here as
   * well: the same running transaction, and the entry becoming visible if and only if
   * that transaction commits.
   * <p>
   * It is what a caller whose call carries the state itself needs, because for such a
   * call the older entry holds the older state - see decision 68 in the repository's
   * DECISIONS.md. Only an operation an extension registered may ask for it, which
   * {@link PhaseTwoCall#replacingWhatIsStillWaiting()} refuses to build otherwise.
   * <p>
   * The default is what an outbox did before replacing existed: the call is discarded
   * against the waiting entry, the older state survives, and a WARN names the store so
   * the application learns why its youngest report never arrived. Quietly keeping the
   * older state is the one outcome which must not happen, which is why a store written
   * outside VanillaBP says so rather than looking as if it had replaced. Every store
   * VanillaBP ships overrides it.
   *
   * @param call The phase-two call to schedule, marked by
   *        {@link PhaseTwoCall#replacingWhatIsStillWaiting()}
   * @return <code>true</code> if the call was scheduled, whether it replaced a waiting
   *         entry or became an entry of its own; <code>false</code> where this store
   *         cannot replace and discarded the call
   */
  default boolean scheduleReplacingWhatIsStillWaiting(
      final PhaseTwoCall call) {

    LoggerFactory
        .getLogger(PhaseTwoOutbox.class)
        .warn(
            """
                Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' asked to \
                replace the entry still waiting for its dispatch, and the outbox store '{}' does not \
                know how to do that. The call is discarded against the waiting entry, so the state \
                which reaches the handler is the OLDER one. Implement \
                PhaseTwoOutbox#scheduleReplacingWhatIsStillWaiting in that store, or use one of the \
                stores VanillaBP ships.""",
            call.operation(),
            call.bpmnProcessId(),
            call.workflowModuleId(),
            call.workflowAggregateId(),
            getClass().getName());
    return schedule(call);

  }

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
   * The default answers an empty set, which means "this store cannot say": nothing is
   * invented at the start then, and the same finding is reported when one of those entries
   * is read for its dispatch instead - the adapter id is at hand there, the read was going
   * to happen anyway, and the answer arrives one dispatch later rather than never. A store
   * whose entries keep the adapter id in a column of its own answers here and is quiet at
   * the dispatch, because the start already said it.
   * <p>
   * A store implementing it answers a cheap query; it is called once per BPMN process at
   * startup and never at runtime.
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

  /**
   * How long the oldest entry waiting for its dispatch has been waiting. It is the
   * number which tells a backlog being worked off from one standing still, and
   * {@link #pendingCalls()} alone cannot: a count which stays at the same value looks
   * the same in both cases, while this age keeps growing only while the same entry
   * keeps waiting.
   * <p>
   * The age is counted from the moment the entry was written, which is the timestamp
   * the dispatch of that entry is measured from as well. It is not the moment of the
   * next attempt: an entry which failed once waits for its backoff, and the operation
   * the application asked for has been owed since it was planned.
   * <p>
   * {@link java.time.Duration#ZERO} where nothing waits, which is the ordinary state of
   * a healthy application and belongs in the graph. {@link java.util.Optional#empty()}
   * means "this store cannot say" - no meter is published then, the same way
   * {@link #pendingCalls()} publishes none. A store which keeps no moment of writing
   * answers empty and says so in its own documentation, because a zero would read as an
   * outbox which is up to date.
   * <p>
   * A store implementing it answers with a cheap query; it is called whenever the
   * metrics backend collects, so an expensive scan does not belong here.
   *
   * @return How long the oldest waiting entry has been waiting, zero where none waits,
   *         or empty if the store cannot say
   */
  default java.util.Optional<java.time.Duration> ageOfOldestPendingCall() {

    return java.util.Optional.empty();

  }

  /**
   * How long an entry written at that moment has been waiting, for the stores which
   * answer {@link #ageOfOldestPendingCall()} and for the wait they report per dispatch.
   * <p>
   * Never negative. The moment comes from the database while the clock comes from this
   * node, and where the two disagree the difference is the disagreement rather than a
   * wait. An age below zero would make a dashboard look broken instead of showing the
   * backlog.
   *
   * @param writtenAt When the entry was written
   * @return How long it has been waiting, at least zero
   */
  static java.time.Duration waitedSince(
      final java.time.Instant writtenAt) {

    final var waited = java.time.Duration.between(writtenAt, java.time.Instant.now());
    return waited.isNegative()
        ? java.time.Duration.ZERO
        : waited;

  }

}
