package io.vanillabp.integration.test.standalone;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The one workflow of the application. Its class sits next to the application class, so
 * both are loaded from the same place.
 */
@Service
@WorkflowService(workflowAggregateClass = StandaloneAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "StandaloneWorkflow"))
public class StandaloneWorkflow {
}
