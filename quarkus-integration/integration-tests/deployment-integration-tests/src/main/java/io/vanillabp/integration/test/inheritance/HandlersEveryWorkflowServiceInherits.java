package io.vanillabp.integration.test.inheritance;

import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The declaration. It is abstract and has a handler of its own. A subclass serves the
 * process which this base declares. The handler methods the subclass inherits belong to
 * that service just like the ones it writes itself.
 * <p>
 * No <code>bpmnProcess</code> is named. So the process gets the name of the class which
 * serves it, and that is the subclass.
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
