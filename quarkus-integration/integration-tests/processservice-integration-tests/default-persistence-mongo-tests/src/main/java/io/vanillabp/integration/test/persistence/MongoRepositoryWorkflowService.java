package io.vanillabp.integration.test.persistence;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = MongoRepositoryAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TestProcess"))
public class MongoRepositoryWorkflowService {

  @Inject
  ProcessService<MongoRepositoryAggregate> processService;

  @Inject
  MongoRepositoryAggregateRepository repository;

  // The aggregate and the outbox entry which plans phase two are written in ONE
  // transaction. So the caller opens one.
  @jakarta.transaction.Transactional
  public MongoRepositoryAggregate startWorkflow(
      final String id) {

    final var aggregate = new MongoRepositoryAggregate();
    aggregate.setId(id);
    aggregate.setStatus("started");
    return processService.startWorkflow(aggregate);

  }

  /**
   * Completes a task of a workflow which is already started. The phase-two test uses it.
   * On a BPMS which needs a two-phase commit, this schedules phase two. Phase two calls back
   * into this application's persistence from the dispatcher's thread.
   *
   * @param id The aggregate's ID
   * @param taskId The task's ID
   */
  public void completeTask(
      final String id,
      final String taskId) {

    processService.completeTask(repository.findById(id), taskId);

  }

  @WorkflowTask
  public void processTask(
      final MongoRepositoryAggregate aggregate) {

    aggregate.setStatus("processed");

  }

}
