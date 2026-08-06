package io.vanillabp.integration.test;

import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@io.vanillabp.spi.service.WorkflowService(workflowAggregateClass = Aggregate.class)
public class WorkflowService {

  @Inject
  ProcessService<Aggregate> processService;

  public Aggregate startWorkflow(
      final String content) {

    final var aggregate = new Aggregate();
    aggregate.setContent(content);
    return processService.startWorkflow(aggregate);

  }

  /**
   * Starts the workflow (again) for an already persisted aggregate - used to test
   * the idempotency of scheduling phase two.
   *
   * @param aggregate The aggregate to start the workflow for
   * @return The attached aggregate
   */
  public Aggregate startWorkflowAgain(
      final Aggregate aggregate) {

    return processService.startWorkflow(aggregate);

  }

  public Aggregate completeTask(
      final Aggregate aggregate,
      final String taskId) {

    return processService.completeTask(aggregate, taskId);

  }

  public Aggregate cancelTask(
      final Aggregate aggregate,
      final String taskId,
      final String bpmnErrorCode) {

    return processService.cancelTask(aggregate, taskId, bpmnErrorCode);

  }

  public Aggregate correlateMessage(
      final Aggregate aggregate,
      final String messageName) {

    return processService.correlateMessage(aggregate, messageName);

  }

  public Aggregate correlateMessage(
      final Aggregate aggregate,
      final String messageName,
      final String correlationId) {

    return processService.correlateMessage(aggregate, messageName, correlationId);

  }

  public Aggregate startWorkflowByMessage(
      final String content,
      final String messageName) {

    final var aggregate = new Aggregate();
    aggregate.setContent(content);
    return processService.startWorkflowByMessage(aggregate, messageName);

  }

  public Aggregate completeUserTask(
      final Aggregate aggregate,
      final String taskId) {

    return processService.completeUserTask(aggregate, taskId);

  }

  public Aggregate cancelUserTask(
      final Aggregate aggregate,
      final String taskId,
      final String bpmnErrorCode) {

    return processService.cancelUserTask(aggregate, taskId, bpmnErrorCode);

  }


  public java.util.List<io.vanillabp.spi.process.ProcessDefinition> getProcessDefinitions(
      final Aggregate aggregate,
      final String historyContext) {

    return processService.getProcessDefinitions(aggregate, historyContext);

  }

  public java.io.InputStream getBpmnXml(
      final String processDefinitionId) {

    return processService.getBpmnXml(processDefinitionId);

  }

  public io.vanillabp.spi.process.WorkflowHistory getWorkflowHistory(
      final Aggregate aggregate,
      final String historyContext) {

    return processService.getWorkflowHistory(aggregate, historyContext);

  }

}
