package io.vanillabp.integration.test.electioncost;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * The workflow service of this scenario. It exists so that the election has a process
 * service to answer from; what it serves is never run.
 */
@Service
@WorkflowService(
    workflowAggregateClass = CostAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "CostProcess"))
public class CostWorkflowService {

  private final ProcessService<CostAggregate> processService;

  public CostWorkflowService(
      final ProcessService<CostAggregate> processService) {

    this.processService = processService;

  }

  public ProcessService<CostAggregate> getProcessService() {

    return processService;

  }

}
