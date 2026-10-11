package io.vanillabp.integration.test.samples.sharedprocess;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * Names the BPMN process itself. So every subclass serves THAT process, and not one named
 * after itself. Several subclasses of one base need this.
 */
@WorkflowService(
    workflowAggregateClass = SharedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "SharedProcess"))
public abstract class SharedProcessBase {

}
