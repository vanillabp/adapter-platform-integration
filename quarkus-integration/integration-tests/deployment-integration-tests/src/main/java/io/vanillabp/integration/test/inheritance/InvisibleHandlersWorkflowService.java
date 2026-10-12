package io.vanillabp.integration.test.inheritance;

import io.vanillabp.extension.sample.SampleNote;
import io.vanillabp.extension.sample.SampleNoteDetails;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Three handler methods which a developer can read in their own source, but VanillaBP
 * cannot:
 * <ul>
 * <li>one which is not public,</li>
 * <li>one which the subclass below overrides without repeating the annotation,</li>
 * <li>one of an EXTENSION which is not public either. The scan of an extension's contract
 * reads the public methods of the class in the same way. So the method is lost for the
 * same reason, and it is named in the same way.</li>
 * </ul>
 */
@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = InvisibleHandlersAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "InvisibleHandlers"))
public class InvisibleHandlersWorkflowService extends OverriddenHandlerBase {

  @WorkflowTask
  protected void tooWellHidden(
      final InvisibleHandlersAggregate aggregate) {

    aggregate.setServedBy("tooWellHidden");

  }

  @SampleNote(element = "TheServiceTask")
  protected SampleNoteDetails noteNobodyReaches(
      final SampleNoteDetails prefilled) {

    return prefilled;

  }

  @Override
  public void overriddenTask(
      final InvisibleHandlersAggregate aggregate) {

    aggregate.setServedBy("overriddenTask");

  }

}
