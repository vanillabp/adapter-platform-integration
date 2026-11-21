package io.vanillabp.extension.sample;

import java.util.Optional;

/**
 * The service which the sample extension offers per workflow aggregate, like the Business
 * Cockpit offers its own. It is injected as
 * <code>SampleNoteService&lt;MyAggregate&gt;</code>, and injecting it is optional.
 *
 * @param <A> The workflow-aggregate type
 */
public interface SampleNoteService<A> {

  /**
   * The note the application's {@link SampleNote} method builds for a BPMN element.
   *
   * @param workflowAggregate The workflow aggregate of the workflow
   * @param elementId The BPMN element the note is about
   * @param kind What happened to it
   * @return The note, or empty where the application has no method for that element. The
   *         fallback is the extension's own business, and here it is "no note"
   */
  Optional<SampleNoteDetails> noteOf(
      A workflowAggregate,
      String elementId,
      SampleNoteDetails.Kind kind);

  /**
   * The same, for an element which this extension can name in more than one way, for
   * example by its BPMN element id and by its task definition. The keys come in the order
   * this extension prefers. The first key which a method of the application serves wins.
   *
   * @param workflowAggregate The workflow aggregate of the workflow
   * @param lookupKeys The keys naming the element, most wanted first
   * @param kind What happened to it
   * @return The note, or empty where no method serves any of the keys
   */
  Optional<SampleNoteDetails> noteOfKeys(
      A workflowAggregate,
      java.util.List<String> lookupKeys,
      SampleNoteDetails.Kind kind);

  /**
   * The same, for an event which knows the version of the BPMN process it happened in.
   * The method which serves that version answers. Where no method serves it, the answer is
   * empty, and the extension falls back to what it prefilled.
   *
   * @param workflowAggregate The workflow aggregate of the workflow
   * @param elementId The BPMN element the note is about
   * @param kind What happened to it
   * @param processVersion The version of the BPMN process, as the BPMS reports it
   * @return The note, or empty where no method serves that element in that version
   */
  Optional<SampleNoteDetails> noteOfVersion(
      A workflowAggregate,
      String elementId,
      SampleNoteDetails.Kind kind,
      String processVersion);

  /**
   * The same, for an event which is more than a read. What the method changed on the
   * workflow aggregate is saved.
   *
   * @param workflowAggregate The workflow aggregate of the workflow
   * @param elementId The BPMN element the note is about
   * @param kind What happened to it
   * @return The note, or empty where the application has no method for that element
   */
  Optional<SampleNoteDetails> recordNoteOf(
      A workflowAggregate,
      String elementId,
      SampleNoteDetails.Kind kind);

  /**
   * The BPMS which holds the workflow of the given aggregate, asked through VanillaBP's
   * election. An extension which simply addressed the first-priority adapter would talk to
   * the wrong BPMS for every workflow which a migration has already moved.
   *
   * @param workflowAggregate The workflow aggregate
   * @return The id of the adapter holding the workflow
   */
  String bpmsHolding(
      A workflowAggregate);

  /**
   * Whether a workflow service of the application claims the given BPMN process of the
   * workflow module of this aggregate. The extension asks <code>WorkflowTaskClaims</code> of
   * the integration SPI. It never asks a type of the adapter SPI.
   *
   * @param bpmnProcessId The BPMN process ID
   * @return Whether a workflow service claims it
   */
  boolean claims(
      String bpmnProcessId);

  /**
   * Whether the given BPMN process of the workflow module of this aggregate works on the
   * same workflow aggregate as the process of this aggregate. An extension which follows a
   * workflow into a called process asks <code>WorkflowTaskClaims</code> of the integration
   * SPI for it.
   *
   * @param otherBpmnProcessId The BPMN process ID to compare with
   * @return Whether both processes work on the same workflow aggregate
   */
  boolean sharesTheWorkflowAggregateWith(
      String otherBpmnProcessId);

  /**
   * The name of the ID property of this workflow aggregate, as <code>WorkflowTaskClaims</code>
   * answers it.
   *
   * @return The name of the ID property
   */
  String workflowAggregateIdName();

  /**
   * The BPMN process ID of this aggregate, spelled the way the BPMS of the given adapter
   * stores it. The extension asks <code>IdentifierScoping</code> of the integration SPI. It
   * never builds the string itself.
   *
   * @param adapterId The adapter ID
   * @return The process ID the BPMS knows
   */
  String scopedProcessIdOn(
      String adapterId);

  /**
   * The configuration of this extension for the workflow module of this aggregate:
   * <code>vanillabp.extensions.sample.greeting</code>, overridden by
   * <code>vanillabp.workflow-modules.&lt;id&gt;.extensions.sample.greeting</code>.
   *
   * @return The configured greeting, or <code>null</code>
   */
  String configuredGreeting();

}
