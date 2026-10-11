package io.vanillabp.integration.test.samples.inheritance;

import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * Carries the declaration, but no bean-defining annotation. It is abstract, so only a
 * subclass can serve anything. Its BPMN process ID follows the class name. In this case the
 * two platforms used to name two different processes.
 */
@WorkflowService(workflowAggregateClass = InheritedAggregate.class)
public abstract class AnnotatedWorkflowServiceBase {

  @WorkflowTask
  public void baseTask() {
  }

}
