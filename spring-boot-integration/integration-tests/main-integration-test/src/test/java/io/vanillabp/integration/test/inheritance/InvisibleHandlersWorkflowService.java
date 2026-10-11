package io.vanillabp.integration.test.inheritance;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Two handler methods which a developer can read in their own source, but VanillaBP cannot.
 * One is not public. The other overrides an annotated method without repeating the
 * annotation.
 */
@WorkflowService(
    workflowAggregateClass = InvisibleHandlersAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "InvisibleHandlers"))
public class InvisibleHandlersWorkflowService extends OverriddenHandlerBase {

  @WorkflowTask
  protected void tooWellHidden(
      final InvisibleHandlersAggregate aggregate) {

    aggregate.setServedBy("tooWellHidden");

  }

  @Override
  public void overriddenTask(
      final InvisibleHandlersAggregate aggregate) {

    aggregate.setServedBy("overriddenTask");

  }

}
