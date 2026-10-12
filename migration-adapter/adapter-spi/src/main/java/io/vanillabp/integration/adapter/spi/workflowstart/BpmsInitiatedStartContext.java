package io.vanillabp.integration.adapter.spi.workflowstart;

import java.util.Map;

import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * All information a BPMS adapter supplies when a BPMS reports the start of a workflow.
 * The adapter creates one context per notification (e.g. a Camunda 7 process-start
 * execution listener or a Camunda 8 start execution-listener job) and passes it to
 * {@link BpmsInitiatedStartInvoker#startWorkflowByBpms(String, String, BpmsInitiatedStartContext)}.
 * The context is neutral on purpose. It carries only values, no BPMS types.
 * <p>
 * What a start means, and why an adapter reports every start event of a process, is
 * decision 69 in the repository's DECISIONS.md.
 */
public interface BpmsInitiatedStartContext {

  /**
   * The BPMN id of the start event which fired. It is used to find the
   * <code>&#64;WorkflowStartedByBpms</code> method, and it is reported to that method.
   *
   * @return The start event's BPMN id
   */
  String getStartEventId();

  /**
   * Which kind of start event fired. It reaches a
   * <code>&#64;WorkflowStartedByBpms</code> method as part of {@link BpmsStartTrigger}, and
   * it decides nothing. What a start means is read from the state of the workflow. That is
   * why an adapter reports every start event of a process, not only the ones its BPMS
   * fires by itself.
   *
   * @return Which kind of start event fired
   */
  BpmsStartTrigger.Kind getKind();

  /**
   * Which signal started the workflow. The application gets it as
   * {@link BpmsStartTrigger#signalName()}. It is the name the model carries. So an adapter
   * which scoped the name on the way to its BPMS reports the plain name again here.
   *
   * @return The PLAIN signal name for {@link BpmsStartTrigger.Kind#SIGNAL},
   *         <code>null</code> otherwise
   */
  default String getSignalName() {

    return null;

  }

  /**
   * The process variables visible at the moment the workflow started. Usually these are
   * values set by a BPMN expression or by an input mapping of the start event. VanillaBP
   * binds them to <code>&#64;TaskParam</code> parameters of the
   * <code>&#64;WorkflowStartedByBpms</code> method.
   * <p>
   * On every BPMS which keeps no business key, they are read for a second reason. There
   * the id of the workflow is kept in the variable named after the id attribute of the
   * workflow aggregate. So VanillaBP finds the name the workflow already has in this map.
   *
   * @return The variables by name. Maybe empty, never <code>null</code>
   */
  default Map<String, Object> getVariables() {

    return Map.of();

  }

  /**
   * The own ID of the BPMS for the started workflow instance. VanillaBP addresses workflows
   * by the ID of the aggregate, so nothing it does depends on this value. But the value is
   * WRITTEN DOWN next to the aggregate when the start is processed. Then a later operation on
   * this workflow does not have to ask every configured BPMS which of them holds it. And an
   * extension can name the workflow on a BPMS whose read model has not caught up yet.
   * <p>
   * The instance meant is the one this start event created. That is the workflow the
   * aggregate IS. Apart from that, the ID still names the workflow in every message about
   * it.
   *
   * @return The native instance ID or <code>null</code>
   */
  default String getNativeInstanceId() {

    return null;

  }

  /**
   * The version of the deployed BPMN process definition the BPMS started this workflow
   * from, as the BPMS counts it. It is matched against
   * <code>&#64;WorkflowStartedByBpms(version = ...)</code>, just like the version of a
   * task is matched against <code>&#64;WorkflowTask(version = ...)</code>. See
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getProcessVersion()}.
   * <code>null</code> is served only by a method which names no version range.
   *
   * @return The process version or <code>null</code>
   */
  default String getProcessVersion() {

    return null;

  }

  /**
   * Whether the aggregate has to be built in the transaction which is already active on
   * the calling thread. That is the case for an embedded BPMS which notifies inside its
   * own engine transaction (e.g. Camunda 7). Otherwise the core opens a new transaction.
   * That is the case for a remote BPMS which delivers the notification on a worker thread
   * (e.g. Camunda 8).
   *
   * @return Whether to join the current transaction
   */
  default boolean runInCurrentTransaction() {

    return false;

  }

  /**
   * The default of this adapter for aggregates without their own
   * {@code @SyncWithBPMS}/{@code @NoSyncWithBPMS} annotation. It decides which values of
   * the aggregate are reported back in {@link BpmsInitiatedStartResult#variables()}.
   * <p>
   * {@link AggregateSyncMode#NONE} switches the reporting off. That is the answer of an
   * EMBEDDED BPMS. It notifies inside its own transaction, so the values would have to be
   * read before the aggregate is committed. And it gets the same values at its next sync
   * point anyway. A remote BPMS answers with its default for sharing, which is
   * {@link AggregateSyncMode#FULL} for every VanillaBP adapter.
   *
   * @return The adapter's sync default
   */
  default AggregateSyncMode getAggregateSyncMode() {

    return AggregateSyncMode.NONE;

  }


  /**
   * The ID of the adapter which delivers this start, which the BPMS did by itself. A
   * delivery PROVES that this BPMS holds the workflow, so VanillaBP records the link. The
   * next operation on that workflow asks the recorded adapter first. A BPMS which is only
   * eventually consistent may not report the workflow yet. It then gets a second look
   * instead of an immediate failure (see {@code WorkflowVisibilityDelay}).
   * <p>
   * The default is <code>null</code>, which records nothing. So an adapter written before
   * this method existed keeps working unchanged.
   *
   * @return The adapter's ID or <code>null</code>
   */
  default String getAdapterId() {

    return null;

  }


  /**
   * The business key the started instance ALREADY carries, if the BPMS keeps such a thing
   * at all. It is the name of the workflow in the BPMS. VanillaBP asks about it first when
   * a start is reported.
   * <p>
   * The contract is the one of
   * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getBusinessKey()}.
   * A business key is only ever a copy of the id of the workflow aggregate. A workflow
   * which VanillaBP started carries it. So a key which names no workflow aggregate belongs
   * to a workflow somebody started under a name of their own, and that start is refused.
   * <p>
   * A BPMS which keeps no business key answers <code>null</code>. It keeps the id in the
   * process variable named after the id attribute of the aggregate, and VanillaBP reads it
   * from {@link #getVariables()} instead. An adapter answers here if its BPMS has a place
   * of its own for the name.
   *
   * @return The business key the instance already carries or <code>null</code>
   */
  default String getBusinessKey() {

    return null;

  }

}
