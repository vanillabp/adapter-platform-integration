package io.vanillabp.integration.runtime.processservice;


import java.util.List;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processervice.MigrationProcessService;
import io.vanillabp.intergration.adapter.migration.spi.MigratableProcessService;
import io.vanillabp.spi.process.ProcessService;
import lombok.extern.slf4j.Slf4j;

/**
 * A {@link ProcessService} bean aware of adapter migration.
 */
@Slf4j
public class ProcessServiceCdiBean<A> extends MigrationProcessService<A> {

  public ProcessServiceCdiBean(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final List<MigratableProcessService<A>> migratableProcessServices) {
    super(workflowModuleId, bpmnProcessId, workflowAggregateClass, properties, migratableProcessServices);
  }

  void startService() {
    initialize();
  }

  void stopService() {
    log.info("Stopping process service: {}", workflowModuleId);
  }

  @Override
  public A startWorkflow(
      A workflowAggregate) throws Exception {
    //return migrationProcessService.startWorkflow(workflowAggregate);
    return workflowAggregate;
  }

  @Override
  public A correlateMessage(
      A workflowAggregate,
      String messageName) {
    //return migrationProcessService.correlateMessage(workflowAggregate, messageName);
    return workflowAggregate;
  }

  @Override
  public A correlateMessage(
      A workflowAggregate,
      String messageName,
      String correlationId) {
    //return migrationProcessService.correlateMessage(workflowAggregate, messageName, correlationId);
    return workflowAggregate;
  }

  @Override
  public A correlateMessage(
      A workflowAggregate,
      Object message) {
    //return migrationProcessService.correlateMessage(workflowAggregate, message);
    return workflowAggregate;
  }

  @Override
  public A correlateMessage(
      A workflowAggregate,
      Object message,
      String correlationId) {
    //return migrationProcessService.correlateMessage(workflowAggregate, message, correlationId);
    return workflowAggregate;
  }

  @Override
  public A completeUserTask(
      A workflowAggregate,
      String taskId) {
    //return migrationProcessService.completeUserTask(workflowAggregate, taskId);
    return workflowAggregate;
  }

  @Override
  public A cancelUserTask(
      A workflowAggregate,
      String taskId,
      String bpmnErrorCode) {
    //return migrationProcessService.cancelUserTask(workflowAggregate, taskId, bpmnErrorCode);
    return workflowAggregate;
  }

  @Override
  public A completeTask(
      A workflowAggregate,
      String taskId) {
    //return migrationProcessService.completeTask(workflowAggregate, taskId);
    return workflowAggregate;
  }

  @Override
  public A cancelTask(
      A workflowAggregate,
      String taskId,
      String bpmnErrorCode) {
    //return migrationProcessService.cancelTask(workflowAggregate, taskId, bpmnErrorCode);
    return workflowAggregate;
  }

}
