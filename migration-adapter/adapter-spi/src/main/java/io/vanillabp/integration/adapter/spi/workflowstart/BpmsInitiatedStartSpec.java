package io.vanillabp.integration.adapter.spi.workflowstart;

import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * One start event a deployed BPMN process can begin with. Every start event of the process
 * is reported, whatever event definition it carries. What a start means is read from the
 * state of the workflow, not from the kind of its start event. The adapter reports it
 * during <code>wireBpmn</code> through
 * {@link BpmsInitiatedStartInvoker#validateBpmsInitiatedStarts}.
 * <p>
 * The start event of an event subprocess is not reported. It fires inside a workflow which
 * already runs, and it starts no workflow.
 * <p>
 * The signal name is the PLAIN one, as modelled. Name-clash avoidance is not visible above
 * the BPMS. So an adapter which scopes identifiers reports what the model said, not what it
 * deployed.
 *
 * @param elementId The BPMN id of the start event
 * @param kind Which kind of start event it is
 * @param signalName The plain signal name for {@link BpmsStartTrigger.Kind#SIGNAL},
 *          <code>null</code> otherwise. It decides nothing. It is shown in the messages
 *          which name the start events of a process, so a reader does not have to look up
 *          an element id in the model
 */
public record BpmsInitiatedStartSpec(
                                     String elementId,
                                     BpmsStartTrigger.Kind kind,
                                     String signalName) {

  /**
   * The spec of a start event about which an adapter knows only its id and its kind. The
   * core matches a reported start event by the element id. So a missing signal name
   * changes no decision. The name only makes the reports about the event easier to read.
   *
   * @param elementId The BPMN id of the start event
   * @param kind Which kind of start event it is
   * @return The spec without a signal name
   */
  public static BpmsInitiatedStartSpec of(
      final String elementId,
      final BpmsStartTrigger.Kind kind) {

    return new BpmsInitiatedStartSpec(elementId, kind, null);

  }

}
