package io.vanillabp.integration.adapter.migration.processervice;

import java.util.List;
import java.util.Map;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.intergration.adapter.migration.spi.MigratableProcessService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
public class MigrationProcessService<A> implements MigratableProcessService<A> {

  protected String workflowModuleId;

  protected String bpmnProcessId;

  protected Class<A> workflowAggregateClass;

  protected Map<String, String> adapters;

  protected List<String> prioritizedAdapters;

  protected List<MigratableProcessService<A>> processServices;

  public MigrationProcessService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final List<MigratableProcessService<A>> processServices) {

    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.workflowAggregateClass = workflowAggregateClass;
    this.adapters = properties.getAdapters();
    this.prioritizedAdapters = properties.getPrioritizedAdaptersFor(workflowModuleId, bpmnProcessId);
    this.processServices = processServices;

  }

  /**
   * Connect to BPMS after bean creation
   */
  protected void initialize() {

  }

  @Override
  public String getWorkflowModuleId() {
    return workflowModuleId;
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
