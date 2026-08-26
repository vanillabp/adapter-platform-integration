package io.vanillabp.integration.test.discovery;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * Carries the annotation of {@link InheritedWorkflowService}. It is no bean itself,
 * so only the subclass can bring this workflow service into the application.
 */
@WorkflowService(
    workflowAggregateClass = InheritedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "InheritedProcess"))
public abstract class AnnotatedWorkflowServiceBase {

}
