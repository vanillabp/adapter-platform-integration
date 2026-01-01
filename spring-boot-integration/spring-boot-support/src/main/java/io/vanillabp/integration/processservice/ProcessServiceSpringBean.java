package io.vanillabp.integration.processservice;

import java.util.List;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processervice.MigrationProcessService;
import io.vanillabp.intergration.adapter.migration.spi.MigratableProcessService;
import io.vanillabp.spi.process.AggregatePersistenceAware;
import io.vanillabp.spi.process.ProcessService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessServiceSpringBean<A> implements ProcessService<A> {

  @Getter
  private final MigrationProcessService<A> migrationProcessService;

  public ProcessServiceSpringBean(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceAware,
      final List<MigratableProcessService<A>> migratableProcessServices) {

    migrationProcessService = new MigrationProcessService<>(
        workflowModuleId, bpmnProcessId, workflowAggregateClass, properties, aggregatePersistenceAware, migratableProcessServices);

  }

  void startService() {

    migrationProcessService.initialize();

  }

  void stopService() {

    log.info("Stopping process service: {}", migrationProcessService.getWorkflowModuleId());

  }

  @Override
  public String getWorkflowModuleId() {

    return migrationProcessService.getWorkflowModuleId();

  }

  private boolean isTransactionActive() {

    return TransactionSynchronizationManager.isActualTransactionActive();

  }

  @Override
  public A startWorkflow(
      A workflowAggregate) {

    return migrationProcessService.startWorkflow(workflowAggregate, isTransactionActive());

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
