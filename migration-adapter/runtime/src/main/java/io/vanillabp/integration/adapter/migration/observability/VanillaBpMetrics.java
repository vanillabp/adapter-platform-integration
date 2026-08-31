package io.vanillabp.integration.adapter.migration.observability;

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
   * Outbox entries waiting to be dispatched, reported by the stores which can count
   * them ({@link io.vanillabp.integration.spi.PhaseTwoOutbox#pendingCalls()}).
   */
  String OUTBOX_PENDING = "vanillabp.outbox.pending";

  String TAG_ADAPTER = "adapter";

  String TAG_WORKFLOW_MODULE = "workflow.module";

  String TAG_BPMN_PROCESS = "bpmn.process";

  String TAG_TASK_DEFINITION = "task.definition";

  String TAG_OUTCOME = "outcome";

  String TAG_OPERATION = "operation";

  String TAG_PERMANENT = "permanent";

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

}
