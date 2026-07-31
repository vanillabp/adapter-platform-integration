package io.vanillabp.integration.test.outbox.mixed;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;

@Service
@WorkflowService(workflowAggregateClass = HotAggregate.class)
public class HotSampleWorkflowService {

  @SuppressWarnings("unused")
  private final ProcessService<HotAggregate> processService;

  public HotSampleWorkflowService(
      final ProcessService<HotAggregate> processService) {

    this.processService = processService;

  }

}
