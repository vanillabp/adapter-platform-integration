package io.vanillabp.integration.adapter.spi.workflowstart;

import java.util.Map;

import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * All information a BPMS adapter supplies when a BPMS reports the start of a workflow.
 * The adapter creates one context per notification (e.g. a Camunda 7 process-start
 * execution listener or a Camunda 8 start execution-listener job) and passes it to
 * {@link BpmsInitiatedStartInvoker#startWorkflowByBpms(String, String, BpmsInitiatedStartContext)}.
 * The context is deliberately neutral: it carries only values, no BPMS types.
 * <p>
 * What a start means, and why an adapter reports every start event of a process, is
 * {@code DECISIONS.pending/653.md}.
 */
public interface BpmsInitiatedStartContext {

  /**
   * The BPMN id of the start event which fired - used to resolve the
   * <code>&#64;WorkflowStartedByBpms</code> method and reported to it.
   *
   * @return The start event's BPMN id
   */
  String getStartEventId();

  /**
   * Which kind of start event fired. It reaches a
   * <code>&#64;WorkflowStartedByBpms</code> method as part of {@link BpmsStartTrigger}
   * and decides nothing: what a start means is read from the state of the workflow, which
   * is why an adapter reports every start event of a process and not only the ones its
   * BPMS fires on its own.
   *
   * @return Which kind of start event fired
   */
  BpmsStartTrigger.Kind getKind();

  /**
   * Which signal started the workflow, reported to the application as
   * {@link BpmsStartTrigger#signalName()}. It is the name the model carries, so an adapter
   * which scoped it on the way to its BPMS reports the plain one again here.
   *
   * @return The PLAIN signal name for {@link BpmsStartTrigger.Kind#SIGNAL},
   *         <code>null</code> otherwise
   */
  default String getSignalName() {

    return null;

  }

  /**
   * The process variables visible at the moment the workflow started - typically values a
   * BPMN expression or an input mapping of the start event set. VanillaBP binds them to
   * <code>&#64;TaskParam</code> parameters of the
   * <code>&#64;WorkflowStartedByBpms</code> method.
   * <p>
   * They are read for a second purpose on every BPMS which keeps no business key: the
   * variable named after the workflow aggregate's id attribute is where the id of the
   * workflow lives there, so this map is where VanillaBP finds the name the workflow
   * already has.
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


  /**
   * The business key the started instance ALREADY carries, where the BPMS keeps such a
   * thing at all. This is the name the workflow goes by in the BPMS, and the first thing
   * VanillaBP asks about a reported start.
   * <p>
   * The contract is the one of
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getBusinessKey()}:
   * a business key is only ever a copy of the workflow aggregate's id. A workflow
   * VanillaBP started carries it, so a key which names no workflow aggregate belongs to a
   * workflow somebody started under a name of their own, and that start is refused.
   * <p>
   * A BPMS which keeps no business key answers <code>null</code> and keeps the id in the
   * process variable named after the aggregate's id attribute, which VanillaBP reads from
   * {@link #getVariables()} instead. Answering here is what an adapter does where its BPMS
   * has a place of its own for the name.
   *
   * @return The business key the instance already carries or <code>null</code>
   */
  default String getBusinessKey() {

    return null;

  }

}
