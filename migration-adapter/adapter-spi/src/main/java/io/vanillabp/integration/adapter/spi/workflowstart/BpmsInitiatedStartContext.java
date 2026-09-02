package io.vanillabp.integration.adapter.spi.workflowstart;

import java.time.Instant;
import java.util.Map;

import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * All information a BPMS adapter supplies when the BPMS started a workflow on its
 * own and the workflow aggregate has to be built. The adapter creates one context
 * per notification (e.g. a Camunda 7 process-start execution listener or a Camunda 8
 * start execution-listener job) and passes it to
 * {@link BpmsInitiatedStartInvoker#startWorkflowByBpms(String, String, BpmsInitiatedStartContext)}.
 * The context is deliberately neutral: it carries only values, no BPMS types.
 */
public interface BpmsInitiatedStartContext {

  /**
   * The BPMN id of the start event which fired - used to resolve the optional
   * <code>&#64;WorkflowStartedByBpms</code> method and reported to it.
   *
   * @return The start event's BPMN id
   */
  String getStartEventId();

  /**
   * @return Which kind of start event fired
   */
  BpmsStartTrigger.Kind getKind();

  /**
   * The instant this start is identified by.
   * <p>
   * The value an adapter reports IDEALLY is the time the engine scheduled the start
   * for, because that is stable: a cyclic timer firing the same instant twice
   * addresses the same workflow aggregate, so a redelivered notification creates
   * nothing twice. Where the BPMS does not hand that time to the adapter - Camunda 7
   * does not give it to a listener - the moment of the notification is reported
   * instead, which is why this is not called a trigger time: an adapter would have to
   * contradict the name.
   *
   * @return The instant, never <code>null</code>
   */
  Instant getStartInstant();

  /**
   * A value identifying THIS start in the BPMS, stable across repeated
   * notifications of it - a remote BPMS typically reports its process instance key
   * here. VanillaBP prefers it over everything else when it derives the workflow
   * aggregate's ID, which is what keeps a redelivered notification from building a
   * second aggregate for a workflow which already has one.
   * <p>
   * An adapter whose notification cannot repeat once the aggregate is committed
   * (an embedded engine writing both in one transaction) reports nothing here, and
   * the aggregate's ID becomes the meaningful one: a timer's trigger time.
   *
   * @return The BPMS' identity of this start or <code>null</code>
   */
  default String getNaturalIdentity() {

    return null;

  }

  /**
   * @return The PLAIN signal name for {@link BpmsStartTrigger.Kind#SIGNAL},
   *         <code>null</code> otherwise
   */
  default String getSignalName() {

    return null;

  }

  /**
   * The process variables visible at the moment the workflow started - typically
   * values a BPMN expression or an input mapping of the start event set. VanillaBP
   * copies them into equally-named attributes of the workflow aggregate and binds
   * them to <code>&#64;TaskParam</code> parameters of the
   * <code>&#64;WorkflowStartedByBpms</code> method.
   *
   * @return The variables by name - possibly empty, never <code>null</code>
   */
  default Map<String, Object> getVariables() {

    return Map.of();

  }

  /**
   * The BPMS' own ID of the started workflow instance. Used for log and error
   * messages only - VanillaBP addresses workflows by the aggregate's ID.
   *
   * @return The native instance ID or <code>null</code>
   */
  default String getNativeInstanceId() {

    return null;

  }

  /**
   * The version of the deployed BPMN process definition the BPMS started this workflow
   * from, as the BPMS counts it. It is matched against
   * <code>&#64;WorkflowStartedByBpms(version = ...)</code> like a task's version is
   * matched against <code>&#64;WorkflowTask(version = ...)</code> - see
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getProcessVersion()}.
   * <code>null</code> matches every method regardless of its version ranges.
   *
   * @return The process version or <code>null</code>
   */
  default String getProcessVersion() {

    return null;

  }

  /**
   * Whether the aggregate has to be built within the transaction already active on
   * the calling thread (an embedded BPMS notifying inside its own engine
   * transaction, e.g. Camunda 7) instead of a new transaction opened by the core (a
   * remote BPMS delivering the notification on a worker thread, e.g. Camunda 8).
   *
   * @return Whether to join the current transaction
   */
  default boolean runInCurrentTransaction() {

    return false;

  }

  /**
   * This adapter's default for aggregates carrying no
   * {@code @SyncWithBPMS}/{@code @NoSyncWithBPMS} annotation of their own - decides
   * which aggregate values are reported back in
   * {@link BpmsInitiatedStartResult#variables()}.
   * <p>
   * {@link AggregateSyncMode#NONE} switches the reporting off, which is the answer of
   * an EMBEDDED BPMS: it notifies inside its own transaction, so the values would
   * have to be read before the aggregate is committed, and it reaches the same values
   * at its next sync point anyway. A remote BPMS answers with its sharing default,
   * which is {@link AggregateSyncMode#FULL} for every VanillaBP adapter.
   *
   * @return The adapter's sync default
   */
  default AggregateSyncMode getAggregateSyncMode() {

    return AggregateSyncMode.NONE;

  }


  /**
   * The ID of the adapter delivering this start the BPMS performed on its own. A delivery PROVES that this BPMS holds
   * the workflow, which is why VanillaBP records the association: the next operation
   * on that workflow probes the recorded adapter first, and an eventually consistent
   * BPMS which does not report the workflow yet gets a second look instead of an
   * immediate failure (see {@code WorkflowVisibilityDelay}).
   * <p>
   * The default is <code>null</code>, which records nothing - an adapter written
   * before this existed keeps working unchanged.
   *
   * @return The adapter's ID or <code>null</code>
   */
  default String getAdapterId() {

    return null;

  }

}
