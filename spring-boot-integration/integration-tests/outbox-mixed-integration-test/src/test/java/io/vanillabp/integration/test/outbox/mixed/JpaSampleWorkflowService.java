package io.vanillabp.integration.test.outbox.mixed;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;

@Service
@WorkflowService(workflowAggregateClass = JpaAggregate.class)
public class JpaSampleWorkflowService {

  @SuppressWarnings("unused")
  private final ProcessService<JpaAggregate> processService;

  public JpaSampleWorkflowService(
      final ProcessService<JpaAggregate> processService) {

    this.processService = processService;

  }

}
