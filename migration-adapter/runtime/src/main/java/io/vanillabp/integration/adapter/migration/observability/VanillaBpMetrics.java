package io.vanillabp.integration.adapter.migration.observability;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

import lombok.Getter;

/**
 * What VanillaBP counts and measures while it delivers work, expressed without any
 * metrics library: the core records, and whoever wants the numbers implements this
 * interface. {@link #NONE} is the implementation of an application which brings no
 * metrics backend at all, and it is what every process service uses until a platform
 * integration hands in a different one.
 * <p>
 * The Micrometer implementation is {@link MicrometerVanillaBpMetrics}, registered by
 * both platform integrations where Micrometer is present. It is the same mechanism
 * the election cache uses
 * ({@link io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheMeters}),
 * and both follow one naming scheme: every meter starts with <code>vanillabp.</code>
 * and names WHAT is measured, while WHERE it happened - the adapter, the workflow
 * module, the BPMN process, the task - is a tag.
 * <p>
 * <b>Cardinality.</b> The tags are values a deployment fixes: the configured adapter
 * ids, the workflow modules of the application, their BPMN processes and the task
 * definitions of those processes. Nothing that grows with the number of workflows -
 * no workflow-aggregate id, no job key, no delivery id - is ever a tag. Those belong
 * to a log line, which is why they are in the MDC instead (see {@link DeliveryMdc}).
 * <p>
 * Why a gauge which cannot be read cheaply is held for a window, why the place is a tag, and why a
 * store which cannot answer leaves a gap instead of reporting zero, is decision 18 in the
 * repository's DECISIONS.md.
 */
public interface VanillaBpMetrics {

  /**
   * Counts nothing - the implementation of an application without a metrics backend.
   */
  VanillaBpMetrics NONE = new VanillaBpMetrics() {
  };

  /**
   * Task deliveries this application processed, by outcome. The rate of this counter
   * is what tells a quiet system from a stalled one.
   */
  String TASK_DELIVERIES = "vanillabp.task.deliveries";

  /**
   * How long a task delivery took, measured around the transaction VanillaBP opens
   * for it - so a slow handler is visible as a slow handler.
   */
  String TASK_DELIVERY_DURATION = "vanillabp.task.delivery.duration";

  /**
   * Repeated deliveries answered from the delivery record instead of running the
   * <code>&#64;WorkflowTask</code> method again. A rising rate means the BPMS hands
   * work out a second time, usually because the lock is too short for the handler.
   */
  String TASK_REDELIVERIES_DEDUPLICATED = "vanillabp.task.redeliveries.deduplicated";

  /**
   * Repeated deliveries which arrived while the first one was still running, so the
   * record could not answer them and the <code>&#64;WorkflowTask</code> method ran a
   * second time. The counterpart of {@link #TASK_REDELIVERIES_DEDUPLICATED}: the two
   * together are what a BPMS handing the same task out twice ends up as, and this one
   * is the half nothing can catch afterwards.
   */
  String TASK_REDELIVERIES_CONCURRENT = "vanillabp.task.redeliveries.concurrent";

  /**
   * Elections which the delivery record of a task answered, so no BPMS was asked which
   * of them holds it. Counted for every operation whose call names a task, the push of
   * a changed aggregate into the scope of one included.
   * <p>
   * Only the answered elections are counted, and there is deliberately no counter for
   * the ones which fell back to the walk: a store which does not implement
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#recordOfTask} answers nothing,
   * which is indistinguishable from a task nobody wrote a record for, and reporting
   * that as a miss would name a defect where there is none. It is read against how many
   * of an application's calls name a task at all.
   */
  String TASK_ELECTIONS_FROM_RECORD = "vanillabp.task.elections.from.record";

  /**
   * Phase-two calls dispatched out of the transaction outbox.
   */
  String OUTBOX_DISPATCHES = "vanillabp.outbox.dispatches";

  /**
   * Dispatches of an outbox entry which was attempted before.
   */
  String OUTBOX_RETRIES = "vanillabp.outbox.retries";

  /**
   * Dispatches which ended in a failure, separated by whether repeating them can
   * help.
   */
  String OUTBOX_FAILURES = "vanillabp.outbox.failures";

  /**
   * Schedules the outbox refused because an operation of the same idempotency key was
   * still waiting for its dispatch, so something the application asked for will not
   * happen. Counted once in the core rather than in each store, so every outbox
   * implementation reports the same number.
   * <p>
   * Two causes end up here and nothing can tell them apart: a redelivered dispatch of a
   * call which was recorded before loses nothing, while a second, legitimate operation
   * of the same key loses everything and leaves a workflow waiting for a message nobody
   * will send again. Every increment writes the WARN of
   * <code>MigrationProcessService</code> as well, which names both of them.
   */
  String OUTBOX_DISCARDED = "vanillabp.outbox.discarded";

  /**
   * Outbox entries a store gave up on. Every store counts one here the moment it writes
   * the block, whether the adapter called the failure permanent or the configured
   * attempts ran out, so this is the number operations alerts on: a blocked entry is an
   * operation the application asked for which will not happen until somebody repairs the
   * row.
   * <p>
   * It is the one meter which has to exist separately, because {@link #OUTBOX_PENDING}
   * counts the entries still waiting and a blocked entry stops waiting. So the gauge an
   * operator watches FALLS at the moment an operation was lost, and nothing else in this
   * interface moves.
   */
  String OUTBOX_BLOCKED = "vanillabp.outbox.blocked";

  /**
   * Outbox entries waiting to be dispatched, reported by the stores which can count
   * them ({@link io.vanillabp.integration.spi.PhaseTwoOutbox#pendingCalls()}).
   */
  String OUTBOX_PENDING = "vanillabp.outbox.pending";

  /**
   * How long an outbox entry waited from the moment it was written to the moment its
   * dispatch ended. It is the gap between the transaction which planned the operation
   * and the BPMS hearing about it, which no counter of this interface shows.
   */
  String OUTBOX_DISPATCH_LAG = "vanillabp.outbox.dispatch.lag";

  /**
   * How long the oldest entry waiting for its dispatch has been waiting, in seconds
   * ({@link io.vanillabp.integration.spi.PhaseTwoOutbox#ageOfOldestPendingCall()}). It
   * is what tells a backlog which is being worked off from one which stands still:
   * {@link #OUTBOX_PENDING} reads the same in both cases.
   */
  String OUTBOX_OLDEST_PENDING_AGE = "vanillabp.outbox.oldest.pending.age";

  /**
   * What the nightly housekeeping of an outbox store did not get to: the dispatched
   * entries whose retention had passed when the window closed. Set once per window and
   * held until the next one, because counting them is a query and this is the answer of
   * a moment which is over.
   * <p>
   * It is read together with {@link #HOUSEKEEPING_REMOVED} and
   * {@link #HOUSEKEEPING_WINDOW_USED}, and alone it says nothing: a store with something
   * left may have run out of window or may not have house-kept at all. The condition to
   * alert on is a window which was used up AND something left over - then the window is
   * too small.
   */
  String HOUSEKEEPING_REMAINING = "vanillabp.outbox.housekeeping.remaining";

  /**
   * How many rows the last housekeeping window removed from an outbox store, entries and
   * payloads together. Set when the window closes and held until the next one.
   */
  String HOUSEKEEPING_REMOVED = "vanillabp.outbox.housekeeping.removed";

  /**
   * How much of the last housekeeping window was used, in seconds. A number close to the
   * length of the configured window means the outbox worked until the deadline, which
   * with {@link #HOUSEKEEPING_REMAINING} above zero is the window being too small.
   */
  String HOUSEKEEPING_WINDOW_USED = "vanillabp.outbox.housekeeping.window.used";

  /**
   * The configured adapter id which did the work - the <code>id</code> of
   * <code>vanillabp.adapters.&lt;id&gt;</code>, not the BPMS type. An application may run
   * two adapters of the same BPMS, and during a migration it usually does, so this tag is
   * what separates the old BPMS from the new one in every number below.
   */
  String TAG_ADAPTER = "adapter";

  /**
   * The workflow module the BPMN process belongs to. It is needed beside
   * {@link #TAG_BPMN_PROCESS} because a BPMN process id is unique inside a module and not
   * across the application: two modules may carry the same id where the BPMS keeps them
   * apart by itself, on Camunda 8 by a tenant (see decision 41 in the repository's
   * DECISIONS.md).
   */
  String TAG_WORKFLOW_MODULE = "workflow.module";

  /**
   * The BPMN process id, the plain one the BPMN file spells. Whatever an adapter adds to
   * it to keep two scopes apart is not part of this tag - {@link #TAG_ADAPTER} says which
   * scope the number belongs to.
   */
  String TAG_BPMN_PROCESS = "bpmn.process";

  /**
   * The key the delivery resolved its <code>&#64;WorkflowTask</code> method by: the task
   * definition of the BPMN task, or its element id where the task names no definition
   * ({@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getTaskDefinition()}).
   * On {@link #TASK_DELIVERY_DURATION} it is the tag which tells one slow handler from a
   * slow application.
   */
  String TAG_TASK_DEFINITION = "task.definition";

  /**
   * How the measured thing ended. Which values occur depends on the meter:
   * {@link DeliveryOutcome} on {@link #TASK_DELIVERIES}, {@link DispatchOutcome} on
   * {@link #OUTBOX_DISPATCH_LAG}. Both know a <code>failed</code>, and it means a different
   * thing on each, so a query names the meter as well as this tag.
   */
  String TAG_OUTCOME = "outcome";

  /**
   * Which operation was carried out, as the persisted name of its
   * {@link io.vanillabp.integration.spi.PhaseOperation}. An operation added later shows
   * up here without anything in this interface changing.
   */
  String TAG_OPERATION = "operation";

  /**
   * Whether repeating the failed operation cannot help, as <code>true</code> or
   * <code>false</code>. A <code>true</code> is a defect somebody has to look at, a
   * <code>false</code> may be a BPMS which was busy for a moment.
   */
  String TAG_PERMANENT = "permanent";

  /**
   * Which outbox store the entry lies in. An application may run more than one - a
   * relational one and a MongoDB one, or gruelbox beside the platform's own store - and
   * they dispatch independently, so a backlog is read per store (see decision 75 in the
   * repository's DECISIONS.md).
   */
  String TAG_STORE = "store";

  /**
   * The value used where a tag has no value at all (an adapter reporting no id, a
   * task delivered without a definition). An empty tag value is dropped by some
   * backends and kept by others, so VanillaBP names the case instead.
   */
  String TAG_VALUE_UNKNOWN = "unknown";

  /**
   * How a task delivery ended, as the <code>outcome</code> tag of
   * {@link #TASK_DELIVERIES} sees it.
   */
  @Getter
  enum DeliveryOutcome {

    /**
     * The handler returned and the task was completed.
     */
    COMPLETED("completed"),

    /**
     * The handler returned and the task stays open for an asynchronous completion
     * (a <code>&#64;TaskId</code> parameter).
     */
    PENDING("pending"),

    /**
     * The handler threw a {@link io.vanillabp.spi.service.TaskException}, so the
     * task ends in a BPMN error.
     */
    BPMN_ERROR("bpmn-error"),

    /**
     * The handler threw anything else: the transaction was rolled back and the BPMS
     * gets the delivery back.
     */
    FAILED("failed");

    private final String tagValue;

    DeliveryOutcome(
        final String tagValue) {

      this.tagValue = tagValue;

    }

  }

  /**
   * How one attempt to dispatch an outbox entry ended, as the <code>outcome</code> tag
   * of {@link #OUTBOX_DISPATCH_LAG} sees it. Whether repeating a failure can help is
   * not repeated here - {@link #OUTBOX_FAILURES} carries that in its
   * <code>permanent</code> tag, and this timer measures a wait rather than a verdict.
   */
  @Getter
  enum DispatchOutcome {

    /**
     * The dispatch returned without an error, so the operation reached the BPMS or
     * whoever else handles it.
     */
    SUCCEEDED("succeeded"),

    /**
     * The dispatch threw. The entry is dispatched again later, and that attempt is
     * measured from the same moment this one was, because the wait of the operation
     * did not start over.
     */
    FAILED("failed");

    private final String tagValue;

    DispatchOutcome(
        final String tagValue) {

      this.tagValue = tagValue;

    }

  }

  /**
   * A task delivery was processed.
   *
   * @param adapterId The id of the adapter which delivered the task
   * @param workflowModuleId The workflow module of the BPMN process
   * @param bpmnProcessId The BPMN process the task belongs to
   * @param taskDefinition The task definition delivered
   * @param outcome How the delivery ended
   * @param durationNanos How long it took, including the transaction
   */
  default void taskDelivered(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition,
      final DeliveryOutcome outcome,
      final long durationNanos) {

  }

  /**
   * A repeated delivery was answered from the delivery record instead of running the
   * handler again.
   *
   * @param adapterId The id of the adapter which delivered the task
   * @param workflowModuleId The workflow module of the BPMN process
   * @param bpmnProcessId The BPMN process the task belongs to
   * @param taskDefinition The task definition delivered
   */
  default void taskRedeliveryDeduplicated(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

  }

  /**
   * A repeated delivery ran at the same time as the delivery it repeats, so both
   * handlers ran and only one record was written.
   *
   * @param adapterId The id of the adapter which delivered the task
   * @param workflowModuleId The workflow module of the BPMN process
   * @param bpmnProcessId The BPMN process the task belongs to
   * @param taskDefinition The task definition delivered
   */
  default void taskRedeliveryRanConcurrently(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

  }

  /**
   * The delivery record of a task answered which adapter holds it, so the walk over the
   * configured BPMS did not run.
   *
   * @param adapterId The id of the adapter the record names
   * @param workflowModuleId The workflow module of the BPMN process
   * @param bpmnProcessId The BPMN process the task belongs to
   * @param operation The persisted name of the operation whose call named the task
   */
  default void taskElectionAnsweredFromRecord(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String operation) {

  }

  /**
   * An outbox entry is about to be dispatched.
   *
   * @param operation The persisted name of the phase-two operation
   * @param previouslyAttempted Whether the entry was dispatched before
   */
  default void outboxDispatchStarted(
      final String operation,
      final boolean previouslyAttempted) {

  }

  /**
   * An outbox dispatch ended in a failure.
   *
   * @param operation The persisted name of the phase-two operation
   * @param permanent Whether repeating the operation cannot help
   *          ({@link io.vanillabp.integration.spi.PhaseTwoPermanentFailure})
   */
  default void outboxDispatchFailed(
      final String operation,
      final boolean permanent) {

  }

  /**
   * An operation was not planned because the outbox found an operation of the same
   * idempotency key still waiting for its dispatch.
   *
   * @param operation The persisted name of the phase-two operation which was dropped
   */
  default void outboxScheduleDiscarded(
      final String operation) {

  }

  /**
   * A store blocked an outbox entry, so the operation it carries will not be carried out
   * until somebody repairs the entry. Counted by the store which writes the block, once
   * per entry.
   *
   * @param store The name of the outbox store which blocked the entry, used as the
   *          <code>store</code> tag - the same value its
   *          {@link #registerPendingOutboxEntries(String, Supplier)} uses
   * @param operation The persisted name of the phase-two operation which was lost
   * @param permanent Whether the adapter said that repeating cannot help
   *          ({@link io.vanillabp.integration.spi.PhaseTwoPermanentFailure}), as opposed
   *          to the configured attempts having run out
   */
  default void outboxEntryBlocked(
      final String store,
      final String operation,
      final boolean permanent) {

  }

  /**
   * Registers where the number of waiting outbox entries is read from. Called once
   * per outbox store by the platform integration, for the stores which can count
   * them.
   * <p>
   * Counting them is a QUERY, and a gauge is read on every collection, so the
   * implementation is expected to hold one measurement for
   * <code>vanillabp.metrics.gauge-cache</code> rather than to ask on every collection
   * (see
   * {@link io.vanillabp.integration.adapter.spi.observability.CachedGaugeValue}). A
   * measurement which could not be taken stays {@link OptionalLong#empty()} all the way
   * to the backend, where it is a gap rather than a zero.
   *
   * @param store The name of the outbox store, used as the <code>store</code> tag
   * @param pending Reports the number of entries waiting to be dispatched, empty where
   *          the store cannot say right now
   */
  default void registerPendingOutboxEntries(
      final String store,
      final Supplier<OptionalLong> pending) {

  }

  /**
   * One attempt to dispatch an outbox entry ended, after the entry had waited.
   * <p>
   * Counted by the store which holds the entry, because only the store knows when the
   * entry was written - {@link #outboxDispatchStarted(String, boolean)} is counted by
   * the core instead. The wait is measured from that moment to the end of this attempt,
   * so a failed attempt reports what the operation has been owed so far and the next
   * attempt reports more.
   *
   * @param store The name of the outbox store, used as the <code>store</code> tag - the
   *          same value its {@link #registerPendingOutboxEntries(String, Supplier)} uses
   * @param outcome How this attempt ended
   * @param waitedNanos How long the entry waited, from the moment it was written to the
   *          end of this attempt
   */
  default void outboxDispatchEnded(
      final String store,
      final DispatchOutcome outcome,
      final long waitedNanos) {

  }

  /**
   * Registers what the nightly housekeeping of one store leaves behind, removes and uses
   * of its window. Called once per store by the housekeeping itself, with three values it
   * holds from one window to the next.
   * <p>
   * One call for all three, because they are one measurement of one night and are read
   * together: a store which published only what was left would say nothing about whether
   * the window was too small or whether it never ran, and that is the question an
   * operator asks. Nothing is queried while a gauge is read, unlike
   * {@link #registerPendingOutboxEntries(String, Supplier)} - the housekeeping measured
   * these when it closed its window.
   *
   * @param store The name of the outbox store, used as the <code>store</code> tag - the
   *          same value its {@link #registerPendingOutboxEntries(String, Supplier)} uses
   * @param remaining What the store still had to remove when the window closed, empty
   *          where no window has closed yet or the count could not be taken
   * @param removed How many rows the last window removed, empty where no window has
   *          closed yet
   * @param windowUsed How much of the window was used, empty where no window has closed
   *          yet
   */
  default void registerHousekeeping(
      final String store,
      final Supplier<OptionalLong> remaining,
      final Supplier<OptionalLong> removed,
      final Supplier<Optional<Duration>> windowUsed) {

  }

  /**
   * Registers where the age of the oldest waiting outbox entry is read from. Called
   * once per outbox store by the platform integration, for the stores which can answer
   * it.
   * <p>
   * Asking is a QUERY and a gauge is read on every collection, so the implementation
   * holds one measurement for <code>vanillabp.metrics.gauge-cache</code>, exactly as
   * {@link #registerPendingOutboxEntries(String, Supplier)} does. An age which could
   * not be read stays {@link Optional#empty()} all the way to the backend,
   * where it is a gap rather than a zero, while an outbox with nothing waiting reports
   * {@link Duration#ZERO} - there the zero is the measurement.
   *
   * @param store The name of the outbox store, used as the <code>store</code> tag
   * @param age Reports how long the oldest waiting entry has been waiting, empty where
   *          the store cannot say
   */
  default void registerAgeOfOldestPendingOutboxEntry(
      final String store,
      final Supplier<Optional<Duration>> age) {

  }

}
