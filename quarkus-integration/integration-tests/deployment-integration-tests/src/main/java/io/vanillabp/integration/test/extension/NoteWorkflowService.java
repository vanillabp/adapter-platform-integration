package io.vanillabp.integration.test.extension;

import io.vanillabp.extension.sample.SampleNote;
import io.vanillabp.extension.sample.SampleNoteDetails;
import io.vanillabp.extension.sample.SampleNoteEvent;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The application's side of the sample extension on Quarkus - the same two methods the
 * Spring Boot integration's scenario has.
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = NoteAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "NoteProcess"))
public class NoteWorkflowService {

  /**
   * Matched by the element the annotation names, and taking every kind of parameter the
   * contract allows.
   *
   * @param aggregate The workflow aggregate VanillaBP loaded
   * @param prefilled The note this extension prefilled
   * @param kind What happened to the element
   * @param kindVariable The same, as a process variable the invocation carried
   * @return The note the extension publishes
   */
  @SampleNote(element = "TheServiceTask")
  public SampleNoteDetails noteOfTheServiceTask(
      final NoteAggregate aggregate,
      final SampleNoteDetails prefilled,
      @SampleNoteEvent final SampleNoteDetails.Kind kind,
      @TaskParam("kind") final String kindVariable) {

    aggregate.setTouched("noteOfTheServiceTask");
    prefilled
        .setTitle("%s/%s/%s/%s".formatted(aggregate.getContent(), prefilled.getTitle(), kind, kindVariable));
    return prefilled;

  }

  /**
   * One of two methods for the same user task, this one naming its BPMN element id. Which
   * of the two runs is decided by the order the extension offers its keys in, and the
   * element id is the one VanillaBP asks for first.
   *
   * @param prefilled The note this extension prefilled
   * @return The note the extension publishes
   */
  @SampleNote(element = NoteTaskWiringSource.ACTIVITY_ID)
  public SampleNoteDetails noteOfTheUserTaskByElementId(
      final SampleNoteDetails prefilled) {

    prefilled.setTitle("by-element-id");
    return prefilled;

  }

  /**
   * The other one, naming the task definition of the same user task.
   *
   * @param prefilled The note this extension prefilled
   * @return The note the extension publishes
   */
  @SampleNote(taskDefinition = NoteTaskWiringSource.TASK_DEFINITION)
  public SampleNoteDetails noteOfTheUserTaskByTaskDefinition(
      final SampleNoteDetails prefilled) {

    prefilled.setTitle("by-task-definition");
    return prefilled;

  }

  /**
   * Matched by its own name, which is the convention VanillaBP's own annotations follow.
   *
   * @param prefilled The note this extension prefilled
   * @return The note the extension publishes
   */
  @SampleNote
  public SampleNoteDetails endOfTheProcess(
      final SampleNoteDetails prefilled) {

    prefilled.setTitle("the end");
    return prefilled;

  }

}
