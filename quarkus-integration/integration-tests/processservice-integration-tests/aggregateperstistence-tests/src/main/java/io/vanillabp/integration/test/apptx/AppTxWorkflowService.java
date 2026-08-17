package io.vanillabp.integration.test.apptx;

import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@WorkflowService(
    workflowAggregateClass = AppTxAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "TestProcess"))
public class AppTxWorkflowService {

  @Inject
  ProcessService<AppTxAggregate> processService;

  @Inject
  AppTxTransactionRunner runner;

  /**
   * Starts a workflow inside the unit of work of the application, which is what an
   * application without JTA-managed persistence does instead of &#64;Transactional.
   *
   * @param id The aggregate's ID
   * @return The attached aggregate
   */
  public AppTxAggregate startWorkflow(
      final String id) {

    return runner.requireNew(() -> {
      final var aggregate = new AppTxAggregate();
      aggregate.setId(id);
      aggregate.setStatus("started");
      return processService.startWorkflow(aggregate);
    });

  }

  /**
   * Starts a workflow WITHOUT opening the application's unit of work - VanillaBP has to
   * refuse that, because the aggregate would be written outside of it.
   *
   * @param id The aggregate's ID
   * @return never
   */
  public AppTxAggregate startWorkflowWithoutTransaction(
      final String id) {

    final var aggregate = new AppTxAggregate();
    aggregate.setId(id);
    aggregate.setStatus("started");
    return processService.startWorkflow(aggregate);

  }

  @WorkflowTask
  public void processTask(
      final AppTxAggregate aggregate) {

    aggregate.setInvocations(aggregate.getInvocations() + 1);
    aggregate.setStatus("processed");

  }

  @WorkflowTask
  public void failingTask(
      final AppTxAggregate aggregate) {

    aggregate.setStatus("must-never-be-visible");
    throw new IllegalStateException("the handler broke");

  }

}
