package io.vanillabp.integration.test.sample;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.inject.Singleton;

@Singleton
@WorkflowService(workflowAggregateClass = Aggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "SampleWorkflow"))
@SuppressWarnings("unused")
public class PartitialSampleWorkflowService {
}
