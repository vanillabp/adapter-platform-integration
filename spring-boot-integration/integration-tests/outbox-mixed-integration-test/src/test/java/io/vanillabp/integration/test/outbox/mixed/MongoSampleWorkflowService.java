package io.vanillabp.integration.test.outbox.mixed;

import org.springframework.stereotype.Service;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.WorkflowService;

@Service
@WorkflowService(workflowAggregateClass = MongoAggregate.class)
public class MongoSampleWorkflowService {

  @SuppressWarnings("unused")
  private final ProcessService<MongoAggregate> processService;

  public MongoSampleWorkflowService(
      final ProcessService<MongoAggregate> processService) {

    this.processService = processService;

  }

}
