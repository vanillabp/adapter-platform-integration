package io.vanillabp.integration.test.extension;

import io.vanillabp.extension.sample.SampleNote;
import io.vanillabp.extension.sample.SampleNoteDetails;
import io.vanillabp.extension.sample.SampleNoteEvent;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The application's side of the sample extension on Quarkus - the same six handler
 * methods which the Spring Boot integration's scenario has ({@code NotedWorkflowService}).
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = NoteAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "NoteProcess"))
public class NoteWorkflowService {

  /**
   * Matched by the element which the annotation names. It takes every kind of parameter
   * which the contract allows.
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
   * One of two methods for the same user task. This one names its BPMN element id. The
   * order in which the extension offers its keys decides which of the two runs. VanillaBP
   * asks for the element id first.
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
   * The other one, which names the task definition of the same user task. It reads a
   * process variable which the method with the element id does not read. So the extension
   * can tell which of the two its keys reach.
   *
   * @param prefilled The note this extension prefilled
   * @param reviewer A process variable only this method reads
   * @return The note the extension publishes
   */
  @SampleNote(taskDefinition = NoteTaskWiringSource.TASK_DEFINITION)
  public SampleNoteDetails noteOfTheUserTaskByTaskDefinition(
      final SampleNoteDetails prefilled,
      @TaskParam("reviewer") final String reviewer) {

    prefilled.setTitle("by-task-definition");
    return prefilled;

  }

  /**
   * One of two methods for one element. This one is for the first version of the model.
   * The version an extension reports picks the method the same way as it picks a
   * <code>&#64;WorkflowTask</code> method. So two generations of a model stand next to each
   * other here as well.
   *
   * @param prefilled The note this extension prefilled
   * @param input A process variable the first generation of the model provides
   * @return The note the extension publishes
   */
  @SampleNote(element = "Activity_TwoGenerations", version = "1")
  public SampleNoteDetails noteOfTheFirstGeneration(
      final SampleNoteDetails prefilled,
      @TaskParam("firstGenerationInput") final String input) {

    prefilled.setTitle("first generation");
    return prefilled;

  }

  /**
   * The other one, for every version deployed after the first.
   *
   * @param prefilled The note this extension prefilled
   * @param input A process variable the later generations of the model provide
   * @return The note the extension publishes
   */
  @SampleNote(element = "Activity_TwoGenerations", version = ">1")
  public SampleNoteDetails noteOfTheLaterGenerations(
      final SampleNoteDetails prefilled,
      @TaskParam("laterGenerationInput") final String input) {

    prefilled.setTitle("later generations");
    return prefilled;

  }

  /**
   * Matched by its own name. VanillaBP's own annotations follow the same convention.
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
