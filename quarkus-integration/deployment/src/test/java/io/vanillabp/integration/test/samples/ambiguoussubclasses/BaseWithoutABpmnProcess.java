package io.vanillabp.integration.test.samples.ambiguoussubclasses;

import io.vanillabp.spi.service.WorkflowService;

/**
 * Names no BPMN process. So every subclass names its process after itself. As soon as there
 * are two subclasses, that is one process too many.
 */
@WorkflowService(workflowAggregateClass = AmbiguousAggregate.class)
public abstract class BaseWithoutABpmnProcess {

}
