package io.vanillabp.integration.test.inheritance;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * Names the BPMN process itself. Several subclasses of one base need that. Without it, each
 * of them would name its process after itself, and one workflow aggregate has only one
 * process to start.
 */
@WorkflowService(
    workflowAggregateClass = SharedProcessAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "SharedProcess"))
public abstract class SharedProcessBase {

}
