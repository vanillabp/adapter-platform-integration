package io.vanillabp.integration.test.sample;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

@Service
@WorkflowService(workflowAggregateClass = Aggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "SampleWorkflow"))
@SuppressWarnings("unused")
public class PartitialSampleWorkflowService {
}
