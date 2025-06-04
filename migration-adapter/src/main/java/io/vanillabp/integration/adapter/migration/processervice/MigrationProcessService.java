package io.vanillabp.integration.adapter.migration.processervice;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class MigrationProcessService<A> implements io.vanillabp.spi.process.ProcessService<A> {

  private final Class<A> workflowAggregateClass;

  @Override
  public A startWorkflow(
      A workflowAggregate) throws Exception {
    log.info("Workflow aggregate class: {}", workflowAggregateClass);
    return null;
  }

  @Override
  public A correlateMessage(
      A workflowAggregate,
      String messageName) {
    return null;
  }

  @Override
  public A correlateMessage(
      A workflowAggregate,
      String messageName,
      String correlationId) {
    return null;
  }

  @Override
  public A correlateMessage(
      A workflowAggregate,
      Object message) {
    return null;
  }

  @Override
  public A correlateMessage(
      A workflowAggregate,
      Object message,
      String correlationId) {
    return null;
  }

  @Override
  public A completeUserTask(
      A workflowAggregate,
      String taskId) {
    return null;
  }

  @Override
  public A cancelUserTask(
      A workflowAggregate,
      String taskId,
      String bpmnErrorCode) {
    return null;
  }

  @Override
  public A completeTask(
      A workflowAggregate,
      String taskId) {
    return null;
  }

  @Override
  public A cancelTask(
      A workflowAggregate,
      String taskId,
      String bpmnErrorCode) {
    return null;
  }

}
