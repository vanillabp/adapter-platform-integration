package io.vanillabp.integration.adapter.migration.observability;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Supplier;

import lombok.Getter;

/**
 * What VanillaBP counts and measures while it delivers work, without any metrics library.
 * The core records, and whoever wants the numbers implements this interface. {@link #NONE}
 * is the implementation for an application without any metrics backend. Every process
 * service uses it until a platform integration hands in a different one.
 * <p>
 * The Micrometer implementation is {@link MicrometerVanillaBpMetrics}. Both platform
 * integrations register it where Micrometer is present. The election cache uses the same
 * mechanism
 * ({@link io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheMeters}),
 * and both follow one naming scheme. Every meter starts with <code>vanillabp.</code> and
 * names WHAT is measured. WHERE it happened (the adapter, the workflow module, the BPMN
 * process, the task) is a tag.
 * <p>
 * <b>Cardinality.</b> The tags are values which a deployment fixes: the configured adapter
 * ids, the workflow modules of the application, their BPMN processes and the task
 * definitions of those processes. Nothing which grows with the number of workflows is ever
 * a tag: no workflow-aggregate id, no job key, no delivery id. Those belong in a log line.
 * That is why they are in the MDC instead (see {@link DeliveryMdc}).
 * <p>
 * Decision 18 in the repository's DECISIONS.md says three things. Why a gauge which cannot
 * be read cheaply is held for a while. Why the place is a tag. And why a store which cannot
 * answer leaves a gap instead of reporting zero.
 */
public interface VanillaBpMetrics {

  /**
   * Counts nothing. This is the implementation for an application without a metrics
   * backend.
   */
  VanillaBpMetrics NONE = new VanillaBpMetrics() {
  };

  /**
   * Task deliveries which this application processed, by outcome. The rate of this counter
   * tells a quiet system from a stalled one.
   */
  String TASK_DELIVERIES = "vanillabp.task.deliveries";

  /**
   * How long a task delivery took, measured around the transaction which VanillaBP opens
   * for it. So a slow handler shows up as a slow handler.
   */
  String TASK_DELIVERY_DURATION = "vanillabp.task.delivery.duration";

  /**
   * Repeated deliveries which the delivery record answered, without running the
   * <code>&#64;WorkflowTask</code> method again. A rising rate means that the BPMS hands out
   * work a second time, usually because the lock is too short for the handler.
   */
  String TASK_REDELIVERIES_DEDUPLICATED = "vanillabp.task.redeliveries.deduplicated";

  /**
   * Repeated deliveries which arrived while the first one was still running. The record
   * could not answer them, so the <code>&#64;WorkflowTask</code> method ran a second time.
   * This is the counterpart of {@link #TASK_REDELIVERIES_DEDUPLICATED}. Together the two
   * show what becomes of a BPMS which hands out the same task twice. This one is the half
   * which nothing can catch afterwards.
   */
  String TASK_REDELIVERIES_CONCURRENT = "vanillabp.task.redeliveries.concurrent";

  /**
   * Task deliveries which this application refused, because it has no workflow aggregate
   * with the ID the BPMS named
   * ({@link io.vanillabp.integration.adapter.spi.workflowtask.DeliveryOfAnUnknownWorkflowException}).
   * Each of them also raises an incident at the BPMS. At the second incident somebody asks:
   * does this happen often? This counter answers that. A steady rate on one BPMN process
   * means that two applications share a BPMS and take each other's work.
   * <p>
   * There is deliberately no delivery record next to it. The application which wrongly got
   * the task would write the record. But whoever investigates reads the records of the
   * application which owns the workflow, and there it would be missing.
   */
  String TASK_DELIVERIES_UNKNOWN_WORKFLOW = "vanillabp.task.deliveries.unknown.workflow";

  /**
   * Elections which the delivery record of a task answered, so that no BPMS was asked which
   * of them holds the task. It is counted for every operation whose call names a task. That
   * includes pushing a changed aggregate into the scope of a task.
   * <p>
   * Only the answered elections are counted. On purpose there is no counter for the ones
   * which fell back to the walk. A store which does not implement
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog#recordOfTask} answers nothing. Nobody
   * can tell that from a task for which nobody wrote a record. Reporting it as a miss would
   * name a defect where there is none. Read this counter against how many calls of an
   * application name a task at all.
   */
  String TASK_ELECTIONS_FROM_RECORD = "vanillabp.task.elections.from.record";

  /**
   * Phase-two calls dispatched out of the transaction outbox.
   */
  String OUTBOX_DISPATCHES = "vanillabp.outbox.dispatches";

  /**
   * Dispatches of an outbox entry which was tried before.
   */
  String OUTBOX_RETRIES = "vanillabp.outbox.retries";

  /**
   * Dispatches which ended in a failure, split by whether repeating them can help.
   */
  String OUTBOX_FAILURES = "vanillabp.outbox.failures";

  /**
   * Schedules which the outbox refused, because an operation with the same idempotency key
   * was still waiting for its dispatch. So something which the application asked for will
   * not happen. It is counted once in the core and not in each store, so every outbox
   * implementation reports the same number.
   * <p>
   * Two causes end up here, and nothing can tell them apart. A repeated dispatch of a call
   * which was recorded before loses nothing. But a second, valid operation with the same key
   * loses everything. It leaves a workflow waiting for a message which nobody will send
   * again. Every increment also writes the WARN of <code>MigrationProcessService</code>,
   * which names both causes.
   */
  String OUTBOX_DISCARDED = "vanillabp.outbox.discarded";

  /**
   * Outbox entries on which a store gave up. Every store counts one here at the moment it
   * writes the block. That is so whether the adapter called the failure permanent or the
   * configured attempts ran out. So operations alert on this number. A blocked entry is an
   * operation which the application asked for, and it will not happen until somebody
   * repairs the row.
   * <p>
   * It is the one meter which has to exist separately. {@link #OUTBOX_PENDING} counts the
   * entries which still wait, and a blocked entry stops waiting. So the gauge which an
   * operator watches FALLS at the moment an operation was lost, and nothing else in this
   * interface moves.
   */
  String OUTBOX_BLOCKED = "vanillabp.outbox.blocked";

  /**
   * Outbox entries waiting to be dispatched, reported by the stores which can count them
   * ({@link io.vanillabp.integration.spi.PhaseTwoOutbox#pendingCalls()}).
   */
  String OUTBOX_PENDING = "vanillabp.outbox.pending";

  /**
   * How long an outbox entry waited, from the moment it was written to the moment its
   * dispatch ended. That is the gap between the transaction which planned the operation and
   * the moment the BPMS hears about it. No counter of this interface shows it.
   */
  String OUTBOX_DISPATCH_LAG = "vanillabp.outbox.dispatch.lag";

  /**
   * How long the oldest entry which waits for its dispatch has been waiting, in seconds
   * ({@link io.vanillabp.integration.spi.PhaseTwoOutbox#ageOfOldestPendingCall()}). It tells
   * a backlog which is being worked off from one which stands still.
   * {@link #OUTBOX_PENDING} reads the same in both cases.
   */
  String OUTBOX_OLDEST_PENDING_AGE = "vanillabp.outbox.oldest.pending.age";

  /**
   * What the nightly housekeeping of an outbox store did not get to: the dispatched entries
   * whose retention had passed when the window closed. It is set once per window and held
   * until the next one. Counting them is a query, and this is the answer of a moment which
   * is over.
   * <p>
   * Read it together with {@link #HOUSEKEEPING_REMOVED} and
   * {@link #HOUSEKEEPING_WINDOW_USED}. Alone it says nothing. A store with something left may
   * have run out of window, or may not have cleaned up at all. Alert on a window which was
   * used up AND something left over. Then the window is too small.
   */
  String HOUSEKEEPING_REMAINING = "vanillabp.outbox.housekeeping.remaining";

  /**
   * How many rows the last housekeeping window removed from an outbox store, entries and
   * payloads together. It is set when the window closes and held until the next one.
   */
  String HOUSEKEEPING_REMOVED = "vanillabp.outbox.housekeeping.removed";

  /**
   * How much of the last housekeeping window was used, in seconds. A number close to the
   * length of the configured window means that the outbox worked until the deadline. If
   * {@link #HOUSEKEEPING_REMAINING} is above zero as well, the window is too small.
   */
  String HOUSEKEEPING_WINDOW_USED = "vanillabp.outbox.housekeeping.window.used";

  /**
   * The configured adapter id which did the work: the <code>id</code> of
   * <code>vanillabp.adapters.&lt;id&gt;</code>, not the BPMS type. An application may run
   * two adapters of the same BPMS, and during a migration it usually does. So this tag
   * separates the old BPMS from the new one in every number below.
   */
  String TAG_ADAPTER = "adapter";

  /**
   * The workflow module which the BPMN process belongs to. It is needed next to
   * {@link #TAG_BPMN_PROCESS}, because a BPMN process id is unique inside a module but not
   * across the application. Two modules may have the same id where the BPMS keeps them
   * apart by itself, on Camunda 8 by a tenant (see decision 41 in the repository's
   * DECISIONS.md).
   */
  String TAG_WORKFLOW_MODULE = "workflow.module";

  /**
   * The plain BPMN process id, as the BPMN file spells it. Whatever an adapter adds to it
   * to keep two scopes apart is not part of this tag. {@link #TAG_ADAPTER} says which scope
   * the number belongs to.
   */
  String TAG_BPMN_PROCESS = "bpmn.process";

  /**
   * The key by which the delivery found its <code>&#64;WorkflowTask</code> method: the task
   * definition of the BPMN task, or its element id where the task names no definition
   * ({@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getTaskDefinition()}).
   * On {@link #TASK_DELIVERY_DURATION} this tag tells one slow handler from a slow
   * application.
   */
  String TAG_TASK_DEFINITION = "task.definition";

  /**
   * How the measured thing ended. Which values occur depends on the meter:
   * {@link DeliveryOutcome} on {@link #TASK_DELIVERIES}, {@link DispatchOutcome} on
   * {@link #OUTBOX_DISPATCH_LAG}. Both have a <code>failed</code>, and it means something
   * different on each. So a query names the meter as well as this tag.
   */
  String TAG_OUTCOME = "outcome";

  /**
   * Which operation was carried out, as the persisted name of its
   * {@link io.vanillabp.integration.spi.PhaseOperation}. An operation added later shows up
   * here, and nothing in this interface has to change.
   */
  String TAG_OPERATION = "operation";

  /**
   * Whether repeating the failed operation cannot help, as <code>true</code> or
   * <code>false</code>. A <code>true</code> is a defect which somebody has to look at. A
   * <code>false</code> may be a BPMS which was busy for a moment.
   */
  String TAG_PERMANENT = "permanent";

  /**
   * Which outbox store the entry is in. An application may run more than one, for example a
   * relational one and a MongoDB one, or gruelbox next to the platform's own store. They
   * dispatch independently, so a backlog is read per store (see decision 75 in the
   * repository's DECISIONS.md).
   */
  String TAG_STORE = "store";

  /**
   * The value used where a tag has no value at all (an adapter which reports no id, a task
   * delivered without a definition). Some backends drop an empty tag value and others keep
   * it, so VanillaBP names the case instead.
   */
  String TAG_VALUE_UNKNOWN = "unknown";

  /**
   * How a task delivery ended, as the <code>outcome</code> tag of {@link #TASK_DELIVERIES}
   * sees it.
   */
  @Getter
  enum DeliveryOutcome {

    /**
     * The handler returned and the task was completed.
     */
    COMPLETED("completed"),

    /**
     * The handler returned, and the task stays open for a completion later
     * (a <code>&#64;TaskId</code> parameter).
     */
    PENDING("pending"),

    /**
     * The handler threw a {@link io.vanillabp.spi.service.TaskException}, so the
     * task ends in a BPMN error.
     */
    BPMN_ERROR("bpmn-error"),

    /**
     * The handler threw anything else. The transaction was rolled back, and the BPMS gets
     * the delivery back.
     */
    FAILED("failed");

    private final String tagValue;

    DeliveryOutcome(
        final String tagValue) {

      this.tagValue = tagValue;

    }

  }

  /**
   * How one attempt to dispatch an outbox entry ended, as the <code>outcome</code> tag of
   * {@link #OUTBOX_DISPATCH_LAG} sees it. Whether repeating a failure can help is not
   * repeated here. {@link #OUTBOX_FAILURES} has that in its <code>permanent</code> tag, and
   * this timer measures a wait, not a verdict.
   */
  @Getter
  enum DispatchOutcome {

    /**
     * The dispatch returned without an error. So the operation reached the BPMS, or
     * whoever else handles it.
     */
    SUCCEEDED("succeeded"),

    /**
     * The dispatch threw. The entry is dispatched again later. That attempt is measured
     * from the same moment as this one, because the wait of the operation did not start
     * over.
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
   * The delivery record answered a repeated delivery, and the handler did not run
   * again.
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
   * A repeated delivery ran at the same time as the delivery it repeats. So both handlers
   * ran, and only one record was written.
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
   * A task delivery was refused, because this application has no workflow aggregate with
   * the ID the BPMS named.
   *
   * @param adapterId The id of the adapter which delivered the task
   * @param workflowModuleId The workflow module of the BPMN process
   * @param bpmnProcessId The BPMN process the task belongs to
   * @param taskDefinition The task definition delivered
   */
  default void taskDeliveredForAnUnknownWorkflow(
      final String adapterId,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String taskDefinition) {

  }

  /**
   * The delivery record of a task answered which adapter holds it. So the walk over the
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
   * An operation was not planned, because the outbox found an operation with the same
   * idempotency key still waiting for its dispatch.
   *
   * @param operation The persisted name of the phase-two operation which was dropped
   */
  default void outboxScheduleDiscarded(
      final String operation) {

  }

  /**
   * A store blocked an outbox entry. So the operation in it will not be carried out until
   * somebody repairs the entry. The store which writes the block counts it, once per
   * entry.
   *
   * @param store The name of the outbox store which blocked the entry, used as the
   *          <code>store</code> tag. It is the same value which its
   *          {@link #registerPendingOutboxEntries(String, Supplier)} uses
   * @param operation The persisted name of the phase-two operation which was lost
   * @param permanent Whether the adapter said that repeating cannot help
   *          ({@link io.vanillabp.integration.spi.PhaseTwoPermanentFailure}), or whether
   *          the configured attempts ran out
   */
  default void outboxEntryBlocked(
      final String store,
      final String operation,
      final boolean permanent) {

  }

  /**
   * Registers where the number of waiting outbox entries is read from. The platform
   * integration calls it once per outbox store, for the stores which can count them.
   * <p>
   * Counting them is a QUERY, and a gauge is read on every collection. So the
   * implementation should hold one measurement for
   * <code>vanillabp.metrics.gauge-cache</code>, and not ask on every collection (see
   * {@link io.vanillabp.integration.adapter.spi.observability.CachedGaugeValue}). A
   * measurement which could not be taken stays {@link OptionalLong#empty()} all the way to
   * the backend. There it is a gap and not a zero.
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
   * The store which holds the entry counts this, because only the store knows when the
   * entry was written. The core counts {@link #outboxDispatchStarted(String, boolean)}
   * instead. The wait is measured from that moment to the end of this attempt. So a failed
   * attempt reports what the operation has been owed so far, and the next attempt reports
   * more.
   *
   * @param store The name of the outbox store, used as the <code>store</code> tag. It is the
   *          same value which its {@link #registerPendingOutboxEntries(String, Supplier)} uses
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
   * Registers what the nightly housekeeping of one store leaves behind, what it removes, and
   * how much of its window it uses. The housekeeping itself calls this once per store, with
   * three values which it holds from one window to the next.
   * <p>
   * There is one call for all three, because they are one measurement of one night and are
   * read together. A store which published only what was left would not say whether the
   * window was too small or whether it never ran. And that is the question an operator
   * asks. Unlike {@link #registerPendingOutboxEntries(String, Supplier)}, nothing is queried
   * while a gauge is read. The housekeeping measured these values when it closed its
   * window.
   *
   * @param store The name of the outbox store, used as the <code>store</code> tag. It is the
   *          same value which its {@link #registerPendingOutboxEntries(String, Supplier)} uses
   * @param remaining What the store still had to remove when the window closed. Empty
   *          where no window has closed yet, or where the count could not be taken
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
   * Registers where the age of the oldest waiting outbox entry is read from. The platform
   * integration calls it once per outbox store, for the stores which can answer it.
   * <p>
   * Asking is a QUERY, and a gauge is read on every collection. So the implementation holds
   * one measurement for <code>vanillabp.metrics.gauge-cache</code>, exactly as
   * {@link #registerPendingOutboxEntries(String, Supplier)} does. An age which could not be
   * read stays {@link Optional#empty()} all the way to the backend. There it is a gap and
   * not a zero. An outbox with nothing waiting reports {@link Duration#ZERO}, and there the
   * zero is the measurement.
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
