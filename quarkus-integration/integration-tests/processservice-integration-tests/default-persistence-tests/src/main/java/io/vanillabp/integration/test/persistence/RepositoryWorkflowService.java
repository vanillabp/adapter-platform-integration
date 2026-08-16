package io.vanillabp.integration.test.persistence;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = RepositoryAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TestProcess"))
public class RepositoryWorkflowService {

  @Inject
  ProcessService<RepositoryAggregate> processService;

  @Inject
  RepositoryAggregateRepository repository;

  public RepositoryAggregate startWorkflow(
      final String id) {

    final var aggregate = new RepositoryAggregate();
    aggregate.setId(id);
    aggregate.setStatus("started");
    return processService.startWorkflow(aggregate);

  }

  /**
   * Completes a task of an already started workflow - used by the phase-two test:
   * on a BPMS needing a two-phase commit this schedules phase two, which calls back
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
      final RepositoryAggregate aggregate) {

    aggregate.setStatus("processed");

  }

}
