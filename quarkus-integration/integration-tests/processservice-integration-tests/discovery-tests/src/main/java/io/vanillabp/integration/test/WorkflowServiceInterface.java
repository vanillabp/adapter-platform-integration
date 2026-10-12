package io.vanillabp.integration.test;

/**
 * The defect: <code>&#64;WorkflowService</code> sits on an interface, and the interface
 * declares the handler as well. Jandex reports the interface as the annotated type. So the
 * interface used to become the workflow service. The methods declared here used to serve
 * the tasks, called on an instance of {@link WorkflowServiceImplementingAnInterface}. See
 * {@code WorkflowServiceOnAnInterfaceTest}.
 */
@io.vanillabp.spi.service.WorkflowService(workflowAggregateClass = Aggregate.class)
public interface WorkflowServiceInterface {

  @io.vanillabp.spi.service.WorkflowTask(taskDefinition = "processTask")
  void processTask(
      Aggregate aggregate);

}
