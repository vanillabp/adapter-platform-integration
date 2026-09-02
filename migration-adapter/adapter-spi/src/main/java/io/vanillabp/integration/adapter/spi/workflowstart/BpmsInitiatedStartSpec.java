package io.vanillabp.integration.adapter.spi.workflowstart;

import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * One start event of a deployed BPMN process which fires WITHOUT the application
 * starting the workflow: a timer, signal or conditional start event. Reported by
 * the adapter during <code>wireBpmn</code> through
 * {@link BpmsInitiatedStartInvoker#validateBpmsInitiatedStarts}.
 * <p>
 * The signal name is the PLAIN one as modelled - name-clash avoidance
 * stays invisible above the BPMS boundary, so an adapter which scopes identifiers
 * reports what the model said, not what it deployed.
 *
 * @param elementId The BPMN id of the start event
 * @param kind Which kind of start event it is
 * @param signalName The plain signal name for {@link BpmsStartTrigger.Kind#SIGNAL},
 *          <code>null</code> otherwise
 * @param description What the event is defined as (e.g. a timer's cycle or date
 *          expression) - used in log and error messages only, may be
 *          <code>null</code>
 */
public record BpmsInitiatedStartSpec(
                                     String elementId,
                                     BpmsStartTrigger.Kind kind,
                                     String signalName,
                                     String description) {

  /**
   * @param elementId The BPMN id of the start event
   * @param kind Which kind of start event it is
   * @return The spec without a signal name and description
   */
  public static BpmsInitiatedStartSpec of(
      final String elementId,
      final BpmsStartTrigger.Kind kind) {

    return new BpmsInitiatedStartSpec(elementId, kind, null, null);

  }

}
