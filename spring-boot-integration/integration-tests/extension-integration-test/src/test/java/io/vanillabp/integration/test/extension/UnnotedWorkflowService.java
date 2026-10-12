package io.vanillabp.integration.test.extension;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * A workflow service which knows nothing about the extension. It has no method with the
 * annotation of the extension, and it does not inject the service of the extension. Both
 * are allowed. The extension asks whether a method exists, and if none does, it uses its
 * own default.
 */
@Service
@WorkflowService(
    workflowAggregateClass = UnnotedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "UnnotedProcess"))
public class UnnotedWorkflowService {

  private final ProcessService<UnnotedAggregate> processService;

  public UnnotedWorkflowService(
      final ProcessService<UnnotedAggregate> processService) {

    this.processService = processService;

  }

  public ProcessService<UnnotedAggregate> getProcessService() {

    return processService;

  }

}
