package io.vanillabp.integration.adapter.migration.processervice;

import java.util.List;
import java.util.Map;

import io.vanillabp.integration.config.MigrationAdapterProperties;
import io.vanillabp.intergration.adapter.migration.spi.MigratableProcessService;
import io.vanillabp.spi.process.ProcessService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class MigrationProcessService<A> implements MigratableProcessService<A>, ProcessService<A> {

  private final Class<A> workflowAggregateClass;

  private final Map<String, String> adapters;

  private final List<String> prioritizedAdapters;

  private final List<MigratableProcessService<A>> processServices;

  public MigrationProcessService(
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final List<MigratableProcessService<A>> processServices) {

    this.workflowAggregateClass = workflowAggregateClass;
    this.adapters = properties.getAdapters();
    // TODO pass workflowModuleId and bpmnProcessId
    this.prioritizedAdapters = properties.getPrioritizedAdaptersFor("test", "test");
    this.processServices = processServices;

  }

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

  @Override
  public Boolean isTaskActive(
      String taskId) {
    return null;
  }
}
