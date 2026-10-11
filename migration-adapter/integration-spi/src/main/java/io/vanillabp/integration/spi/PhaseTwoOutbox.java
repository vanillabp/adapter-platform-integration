package io.vanillabp.integration.spi;

import org.slf4j.LoggerFactory;

/**
 * Phase two is a MOMENT, not a kind of call. Phase one is the transaction of the caller.
 * Phase two is everything which runs after it committed. This outbox carries whatever was
 * planned for that moment: a call to a BPMS, or an operation an extension registered for
 * itself.
 * <p>
 * VanillaBP itself schedules the second phase of a BPMS call with a two-phase commit here.
 * A remote BPMS cannot take part in the local transaction which persisted the workflow
 * aggregate. So the call is split in two, and this store makes its second half reliable.
 * <p>
 * A store has to implement one method, {@link #schedule(PhaseTwoCall)}. It never learns
 * which operations exist. A call carries the NAME of the operation, its arguments and its
 * idempotency key, and all of them are opaque here. The core builds those calls from the
 * {@link PhaseOperation} the application asked for. The dispatch goes through the
 * <code>PhaseTwoRouter</code> of the core, which routes the call to the
 * <code>MigrationProcessService</code> of the workflow module and BPMN process.
 * <p>
 * An EXTENSION uses the same outbox for after-commit work of its own. It registers
 * an operation in the {@link PhaseOperationRegistry}, builds its calls with
 * {@link PhaseTwoCall#of(PhaseOperation, String, String, String, String, java.util.Map)}
 * and schedules them here. The store treats them like any other entry, and the
 * router dispatches them to the own handler of the extension.
 * <p>
 * <strong>A call which carries a payload:</strong> a caller may pass bytes together with
 * the identifiers ({@link PhaseTwoCall#payload()}). A store has to keep them until the
 * call is dispatched, in the same transaction as the entry. The stores VanillaBP ships put
 * them into a {@link PhaseTwoPayloadStore} next to the entry, and persist only the
 * reference. That keeps an entry a row of identifiers (decision 62 in the
 * repository's DECISIONS.md). A store of the application's own may do it in another way.
 * For example, a store which keeps the whole call in memory until the commit already has
 * the bytes and needs nothing more. But no store may drop them. A handler would then be
 * told a state which is not the one the caller saw.
 * <p>
 * The platform integrations (e.g. based on JDBC, JPA or MongoDB) or the business
 * application itself provide the implementations, because the platform-neutral core
 * must not depend on any particular persistence technology.
 * <p>
 * <strong>Scheduling contract:</strong> call {@link #schedule(PhaseTwoCall)} inside the
 * local transaction which persists the workflow aggregate, while it still runs. The
 * implementation MUST enlist the outbox entry in exactly that transaction. The entry
 * becomes visible if and only if the transaction commits. So "aggregate persisted" and
 * "phase two will run" happen together or not at all. This prevents ghost workflows in
 * the BPMS, and aggregates without workflows.
 * <p>
 * <strong>Idempotency contract:</strong> implementations MUST enforce that
 * {@link PhaseTwoCall#idempotencyKey()} (where present) is unique AMONG THE ENTRIES WHICH
 * STILL WAIT FOR THEIR DISPATCH. They use the unique constraint mechanism of the store
 * (unique index or constraint on the persisted key). A key says "this operation is planned
 * once", not "this operation ever happened". If a call has the key of an entry which has
 * not been dispatched yet, one of the two cases below happens. The same call after that
 * entry reached the BPMS is a NEW operation, and it is scheduled. So a second round of a
 * loop, or a second element of a multi-instance activity, correlates the same message
 * again. It does not wait forever for a message VanillaBP silently dropped. See
 * decision 22 in the repository's DECISIONS.md, and
 * {@link #schedule(PhaseTwoCall)} for what a store logs about a discard.
 * <p>
 * The CALL itself says which of the two happens. It is never a setting:
 * <ul>
 * <li>A plain call is discarded, and {@link #schedule(PhaseTwoCall)} answers
 * <code>false</code>. The waiting entry stays as it is. That is right if a call
 * carries the intention, and the dispatch reads the state fresh.</li>
 * <li>A call marked by {@link PhaseTwoCall#replacingWhatIsStillWaiting()} takes the
 * place of the waiting entry, payload included, in the transaction it is scheduled in.
 * {@link #scheduleReplacingWhatIsStillWaiting(PhaseTwoCall)} answers <code>true</code>.
 * The waiting reports of one workflow still collapse into one, and the one which is
 * left carries the youngest state. See decision 68 in the repository's
 * DECISIONS.md.</li>
 * </ul>
 * <strong>An entry which a dispatch has already taken is never replaced.</strong>
 * It runs to its end. The younger call becomes an entry of its own, and it takes no part
 * in the deduplication of that key, because the key belongs to the entry which is on its
 * way. Then two calls reach the handler where one was asked for. That is the price for
 * never taking work away from a dispatch which may already have reached the BPMS.
 * <p>
 * <strong>What the dispatch reads is what was written last.</strong> Removing the
 * replaced payload, writing the younger one and pointing the entry at it belong to the
 * transaction the call was scheduled in, just like the entry itself. So no reader meets
 * an entry whose payload is gone. And a rollback leaves the replaced entry and its
 * payload as they were. Some stores cannot enlist at all. The MongoDB stores without a
 * replica set say so in their own javadoc. There a replacement is best-effort, just like
 * an insert.
 * <p>
 * The narrower window does NOT protect against two entries planned in the same batch of
 * work. Multi-instance siblings of one aggregate share module, process and aggregate ID,
 * and only their correlation id is left to tell them apart. If it is the same, one of
 * them is discarded and the store says so. The caller has to use a different correlation
 * id for each element.
 * <p>
 * <strong>Recovery contract:</strong> implementations must dispatch every entry which
 * was committed but not processed
 * <ul>
 * <li>right after the local transaction was committed and</li>
 * <li>after an application restart (crash recovery), by polling for left-over
 * entries.</li>
 * </ul>
 * If a dispatch fails, the entry has to be retried with a backoff.
 * <p>
 * <strong>DONE instead of delete:</strong> a successful dispatch marks the entry as
 * DONE. It is NOT deleted at once. It is deleted asynchronously after a retention
 * period you can configure (<code>vanillabp.outbox.*</code>, default 7 days). The
 * retention gives you a dispatched entry somebody can still look at during support. It
 * does NOT extend the deduplication window, which ends with the dispatch. So a store has
 * to take the key of a dispatched entry out of whatever enforces uniqueness, and say in
 * its own javadoc how it does that.
 * <p>
 * <strong>Rest window of at-least-once:</strong> a crash between the call to the remote
 * BPMS and marking the entry DONE dispatches the entry again on recovery. The narrower
 * deduplication window does not widen this one. A second dispatch reads the very entry
 * which is still not DONE. So the bookkeeping of attempts by the store carries the
 * guarantee, never the idempotency key. That is gruelbox' bookkeeping, or the
 * STATUS/ATTEMPTS columns of the stores VanillaBP wrote itself. This rest window is
 * accepted (eventual consistency). Adapters keep their operations idempotent, as the
 * {@code PhaseOperationHandler} of the adapter SPI demands for its phase two. The window
 * is made as small as possible (but not closed) for START operations. A store which
 * passes &quot;this entry was dispatched before&quot; to the dispatch method of the router
 * makes it ask {@code MigratableProcessService#awarenessOfWorkflowForRedispatch} of the
 * recorded adapter. If the workflow is already known, the entry is used up without a
 * second start.
 * <p>
 * <strong>Poison entries:</strong> entries which fail again and again are blocked after a
 * number of attempts you can configure. They are left in the store as a trail you can
 * monitor. The implementation logs an ERROR which names workflow module, BPMN process,
 * aggregate ID and operation. Some dispatch failures are caused by a BPMN process which
 * is no longer part of the application, or by an adapter ID which is no longer
 * configured (a stale entry after a configuration change). They give messages which name
 * that case and guide the developer.
 * <p>
 * Why an operation which moves the workflow forward is planned here, and not executed in the
 * transaction of the caller, and why an operation which nothing can deduplicate carries no
 * idempotency key, is decision 2 in the repository's DECISIONS.md.
 */
public interface PhaseTwoOutbox {

  /**
   * Schedules the given phase-two call. Call it inside the local transaction which
   * persists the workflow aggregate, while it still runs. The implementation MUST enlist
   * in that transaction (the entry becomes visible if and only if the transaction
   * commits).
   * <p>
   * The return value matters. A <code>false</code> means that an operation the
   * application asked for will not happen. The store logs it, and names both causes it
   * cannot tell apart: an at-least-once dispatch which was delivered again, or a real
   * second operation which lost against one still waiting. The core reports it to
   * whoever called.
   * <p>
   * A call marked by {@link PhaseTwoCall#replacingWhatIsStillWaiting()} takes the place
   * of the waiting entry here as well, because the mark travels in the call. But
   * {@link #scheduleReplacingWhatIsStillWaiting(PhaseTwoCall)} adds a warning for a store
   * which never learned to replace. So use that one.
   *
   * @param call The phase-two call to schedule
   * @return <code>true</code> if the call was scheduled, <code>false</code> if an
   *         entry with the same {@link PhaseTwoCall#idempotencyKey()} is still
   *         waiting for its dispatch (no-op)
   */
  boolean schedule(
      PhaseTwoCall call);

  /**
   * Schedules the given phase-two call, so that it takes the place of the entry with the
   * same {@link PhaseTwoCall#idempotencyKey()} which still waits for its dispatch, payload
   * included. Everything {@link #schedule(PhaseTwoCall)} demands holds here as well: the
   * same running transaction, and the entry becomes visible if and only if that
   * transaction commits.
   * <p>
   * A caller needs this if its call carries the state itself. For such a call the older
   * entry holds the older state. See decision 68 in the repository's
   * DECISIONS.md. Only an operation an extension registered may ask for it.
   * {@link PhaseTwoCall#replacingWhatIsStillWaiting()} refuses to build it otherwise.
   * <p>
   * The default does what an outbox did before replacing existed. The call is discarded
   * against the waiting entry, and the older state survives. A WARN names the store, so
   * the application learns why its youngest report never arrived. Keeping the older state
   * without a word is the one result which must not happen. That is why a store written
   * outside VanillaBP says so, and does not look as if it had replaced. Every store
   * VanillaBP ships overrides it.
   *
   * @param call The phase-two call to schedule, marked by
   *        {@link PhaseTwoCall#replacingWhatIsStillWaiting()}
   * @return <code>true</code> if the call was scheduled, whether it replaced a waiting
   *         entry or became an entry of its own; <code>false</code> if this store
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
   * The adapter ids which the entries of the BPMN process of one workflow module wait for.
   * That is every entry which is not DONE yet and names an adapter
   * ({@link PhaseTwoCall#adapterId()}, set for the START operations. The operations which
   * find their adapter by asking carry none, and they are not part of any answer).
   * <p>
   * This is asked once at startup, for one question only. If entries wait for an adapter
   * id which is not configured any more, the id was RENAMED or removed too early. Both end
   * the same way, and today only the dispatch says so. The entry fails, is repeated and is
   * finally blocked, while the workflow it would have started was persisted long ago.
   * <p>
   * The default answers an empty set, which means "this store cannot say". Then nothing is
   * made up at the start. Instead the same finding is reported when one of those entries is
   * read for its dispatch. The adapter id is at hand there, the read happens anyway, and the
   * answer arrives one dispatch later instead of never. A store whose entries keep the
   * adapter id in a column of its own answers here, and is quiet at the dispatch, because
   * the start already said it.
   * <p>
   * A store which implements it answers a cheap query. It is called once per BPMN process
   * at startup, and never at runtime.
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
   * How many entries wait to be dispatched right now. This is the number an operator
   * looks at first when a BPMS cannot be reached. A broken connection piles up in phase
   * two, and a rising number says that the application is fine but the BPMS is not.
   * <p>
   * The default is {@link java.util.OptionalLong#empty()}, which means "this store
   * cannot say". Then no meter is published. That is honest, while a zero would be a
   * claim. A store which implements it answers with a cheap query. It is called
   * whenever the metrics backend collects, so an expensive scan does not belong here.
   *
   * @return The number of entries waiting, or empty if the store cannot count them
   */
  default java.util.OptionalLong pendingCalls() {

    return java.util.OptionalLong.empty();

  }

  /**
   * How long the oldest entry which waits for its dispatch has been waiting. This number
   * tells a backlog which is being worked off from one which stands still.
   * {@link #pendingCalls()} alone cannot do that. A count which stays at the same value
   * looks the same in both cases. This age only keeps growing while the same entry keeps
   * waiting.
   * <p>
   * The age counts from the moment the entry was written. The dispatch of that entry is
   * measured from the same timestamp. It is not the moment of the next attempt. An entry
   * which failed once waits for its backoff, but the application has been owed the
   * operation since it was planned.
   * <p>
   * {@link java.time.Duration#ZERO} if nothing waits. That is the normal state of a
   * healthy application, and it belongs in the graph. {@link java.util.Optional#empty()}
   * means "this store cannot say". Then no meter is published, in the same way
   * {@link #pendingCalls()} publishes none. A store which keeps no moment of writing
   * answers empty and says so in its own documentation, because a zero would read as an
   * outbox which is up to date.
   * <p>
   * A store which implements it answers with a cheap query. It is called whenever the
   * metrics backend collects, so an expensive scan does not belong here.
   *
   * @return How long the oldest waiting entry has been waiting, zero if none waits,
   *         or empty if the store cannot say
   */
  default java.util.Optional<java.time.Duration> ageOfOldestPendingCall() {

    return java.util.Optional.empty();

  }

  /**
   * How long an entry written at that moment has been waiting. It is for the stores which
   * answer {@link #ageOfOldestPendingCall()}, and for the wait they report per dispatch.
   * <p>
   * Never negative. The moment comes from the database, while the clock comes from this
   * node. If the two disagree, the difference is that disagreement and not a wait. An
   * age below zero would make a dashboard look broken, instead of showing the backlog.
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
