package io.vanillabp.integration.test.outbox;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;

@Service
@WorkflowService(workflowAggregateClass = Aggregate.class)
public class SampleWorkflowService {

  private final ProcessService<Aggregate> processService;

  public SampleWorkflowService(
      final ProcessService<Aggregate> processService) {

    this.processService = processService;

  }

}
