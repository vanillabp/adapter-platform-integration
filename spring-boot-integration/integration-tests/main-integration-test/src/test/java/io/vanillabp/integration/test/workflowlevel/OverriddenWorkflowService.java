package io.vanillabp.integration.test.workflowlevel;

import org.springframework.beans.factory.annotation.Autowired;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * Bound to BPMN process 'DummyProcess' (the process ID the dummy adapter reports from
 * readBpmn) - the workflow whose prioritized adapters are overridden by
 * {@code WorkflowLevelOverrideTest}. See {@link OverriddenAggregate} for why this
 * lives in its own package.
 */
@WorkflowService(
    workflowAggregateClass = OverriddenAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "DummyProcess"))
public class OverriddenWorkflowService {

  @Autowired
  private ProcessService<OverriddenAggregate> processService;

}
