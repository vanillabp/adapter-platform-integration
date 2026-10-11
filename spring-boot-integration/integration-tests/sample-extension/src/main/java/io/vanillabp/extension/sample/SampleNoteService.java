package io.vanillabp.extension.sample;

import java.util.Optional;

/**
 * The service the sample extension offers per workflow aggregate, just as the Business
 * Cockpit offers its own. The application injects it as
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
   * @return The note, or empty if the application has no method for that element. The
   *         extension decides on the fallback, and here it is "no note"
   */
  Optional<SampleNoteDetails> noteOf(
      A workflowAggregate,
      String elementId,
      SampleNoteDetails.Kind kind);

  /**
   * The same, for an element this extension can name in more than one way. For example,
   * by its BPMN element id and by the task definition of the same element. The keys come
   * in the order this extension prefers. The first key which a method of the application
   * serves wins.
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
   * The method which serves that version answers. If no method serves it, the answer is
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
   * The same, for an event which is more than a read. VanillaBP saves what the method
   * changed on the workflow aggregate.
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
   * The BPMS which holds the workflow of the given aggregate, found by the election of
   * VanillaBP. An extension which always used the adapter of the first priority would
   * talk to the wrong BPMS for every workflow a migration has already moved.
   *
   * @param workflowAggregate The workflow aggregate
   * @return The id of the adapter holding the workflow
   */
  String bpmsHolding(
      A workflowAggregate);

  /**
   * The configuration of this extension for the workflow module of this aggregate. That
   * is <code>vanillabp.extensions.sample.greeting</code>, and
   * <code>vanillabp.workflow-modules.&lt;id&gt;.extensions.sample.greeting</code> overrides
   * it.
   *
   * @return The configured greeting, or <code>null</code>
   */
  String configuredGreeting();

}
