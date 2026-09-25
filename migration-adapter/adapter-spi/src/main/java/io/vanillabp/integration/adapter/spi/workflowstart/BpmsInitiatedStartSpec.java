package io.vanillabp.integration.adapter.spi.workflowstart;

import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * One start event a deployed BPMN process can begin with. Every start event of the process
 * is reported, whichever event definition it carries, because what a start means is read
 * from the state of the workflow and not from the kind of its start event. Reported by the
 * adapter during <code>wireBpmn</code> through
 * {@link BpmsInitiatedStartInvoker#validateBpmsInitiatedStarts}.
 * <p>
 * A start event of an event subprocess is not one of them: it fires inside a workflow which
 * already runs and starts none.
 * <p>
 * The signal name is the PLAIN one as modelled - name-clash avoidance
 * stays invisible above the BPMS boundary, so an adapter which scopes identifiers
 * reports what the model said, not what it deployed.
 *
 * @param elementId The BPMN id of the start event
 * @param kind Which kind of start event it is
 * @param signalName The plain signal name for {@link BpmsStartTrigger.Kind#SIGNAL},
 *          <code>null</code> otherwise. Nothing is decided by it: it is shown in the
 *          messages naming the start events of a process, so a reader does not have to
 *          look an element id up in the model
 */
public record BpmsInitiatedStartSpec(
                                     String elementId,
                                     BpmsStartTrigger.Kind kind,
                                     String signalName) {

  /**
   * The spec of a start event an adapter can say no more about than its id and its kind.
   * The core matches a reported start event by the element id, so the signal name left
   * out changes no decision - it makes what is reported about the event easier to read.
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
