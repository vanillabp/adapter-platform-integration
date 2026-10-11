package io.vanillabp.integration.test.inheritance;

import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The declaration. It is abstract and has a handler of its own. It names no
 * <code>bpmnProcess</code>, so the process is named after the class which serves it. That is
 * the subclass.
 */
@WorkflowService(workflowAggregateClass = InheritedAggregate.class)
public abstract class HandlersEveryWorkflowServiceInherits {

  @WorkflowTask
  public void inheritedTask(
      final InheritedAggregate aggregate) {

    aggregate.setServedBy(aggregate.getServedBy()
        + "+inheritedTask");

  }

}
