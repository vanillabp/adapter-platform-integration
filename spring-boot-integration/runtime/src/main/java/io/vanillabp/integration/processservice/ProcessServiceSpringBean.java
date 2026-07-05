package io.vanillabp.integration.processservice;

import java.io.InputStream;
import java.util.List;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.aggregate.AggregatePersistenceAware;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.ProcessDefinitionNotFoundException;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.WorkflowHistory;
import io.vanillabp.spi.process.WorkflowNotFoundException;
import jakarta.transaction.Transactional;
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

    migrationProcessService = new MigrationProcessService<A>(
        workflowModuleId, bpmnProcessId, workflowAggregateClass, properties, new AggregatePersistenceAwareWrapper<A>(aggregatePersistenceAware), migratableProcessServices, null);

  }

  void startService() {

    migrationProcessService.initialize();

  }

  /**
   * Stops the process service. Called by
   * {@link io.vanillabp.integration.deployment.SpringBootDeploymentService} on
   * graceful shutdown of the application.
   */
  public void stopService() {

    log.info("Stopping process service: {}", migrationProcessService.getWorkflowModuleId());

  }

  @Override
  public String getWorkflowModuleId() {

    return migrationProcessService.getWorkflowModuleId();

  }

  public A startWorkflow(
      A workflowAggregate) {

    if (migrationProcessService.needsTransactionForStartingWorkflows() && noTransactionIsActive()) {
      throw new RuntimeException(
          "No transaction active available! Add run 'startWorkflow' only having a local transaction active.");
    }

    return migrationProcessService.startWorkflow(workflowAggregate);

  }

  @Transactional
  public void startWorkflowPhaseTwo(
      final String adapterId,
      final Object workflowAggregateId) {

    migrationProcessService.startWorkflowPhaseTwo(adapterId, workflowAggregateId);

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

  @Override
  public List<ProcessDefinition> getProcessDefinitions(
      final A workflowAggregate,
      final String historyContext) throws WorkflowNotFoundException {

    return List.of();
  }

  @Override
  public InputStream getBpmnXml(
      final String processDefinitionId) throws ProcessDefinitionNotFoundException {

    return null;
  }

  @Override
  public WorkflowHistory getWorkflowHistory(
      final A workflowAggregate,
      final String historyContext) throws WorkflowNotFoundException {

    return null;
  }

  public boolean transactionIsActive() {

    return !noTransactionIsActive();

  }

  private boolean noTransactionIsActive() {

    return !TransactionSynchronizationManager.isActualTransactionActive();

  }

}
