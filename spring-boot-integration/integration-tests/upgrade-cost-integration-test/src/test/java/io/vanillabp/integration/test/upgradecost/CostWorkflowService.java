package io.vanillabp.integration.test.upgradecost;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The workflow service of this scenario. Its handler does nothing, on purpose: what an
 * application does inside its own handler is the application's time, and on version 1 it ran
 * inside the engine's job transaction while on version 2 it runs inside a transaction
 * VanillaBP opens. That difference is real and it is a sentence of the documentation rather
 * than a number of this measurement.
 */
@Service
@WorkflowService(
    workflowAggregateClass = CostAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = CostWorkflowService.PROCESS))
public class CostWorkflowService {

  /**
   * The BPMN process this service serves.
   */
  public static final String PROCESS = "UpgradeCostProcess";

  /**
   * The task definition of the one service task of that process.
   */
  public static final String TASK = "doNothing";

  private final ProcessService<CostAggregate> processService;

  public CostWorkflowService(
      final ProcessService<CostAggregate> processService) {

    this.processService = processService;

  }

  public ProcessService<CostAggregate> getProcessService() {

    return processService;

  }

  /**
   * The handler of the one service task. It does nothing, so what the measurement counts is
   * what VanillaBP writes around it.
   *
   * @param aggregate The workflow aggregate VanillaBP loaded
   */
  @WorkflowTask(taskDefinition = TASK)
  public void doNothing(
      final CostAggregate aggregate) {

  }

}
