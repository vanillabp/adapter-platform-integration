package io.vanillabp.integration.processservice;

import java.util.List;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.processservice.ProcessServiceBase;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ProcessServiceSpringBean<A> extends ProcessServiceBase<A> {

  @Getter
  private final MigrationProcessService<A> migrationProcessService;

  public ProcessServiceSpringBean(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceAware,
      final List<MigratableProcessService<A>> migratableProcessServices,
      final PhaseTwoOutboxResolver phaseTwoOutboxResolver,
      final PhaseTwoRouter phaseTwoRouter,
      final WorkflowAdapterCache workflowAdapterCache) {

    migrationProcessService = new MigrationProcessService<A>(
        workflowModuleId, bpmnProcessId, workflowAggregateClass, properties, aggregatePersistenceAware, migratableProcessServices, phaseTwoOutboxResolver, workflowAdapterCache);

    // register as phase-two dispatch target: outbox entries for this workflow
    // module/BPMN process are routed here after the local transaction was committed
    if (phaseTwoRouter != null) {
      phaseTwoRouter.register(migrationProcessService);
    }

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

  public String getBpmnProcessId() {

    return migrationProcessService.getBpmnProcessId();

  }

  public Class<A> getWorkflowAggregateClass() {

    return migrationProcessService.getWorkflowAggregateClass();

  }

  @Override
  public A startWorkflow(
      final A workflowAggregate) {

    if (migrationProcessService.needsTwoPhaseCommitForStartingWorkflows() && noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.startWorkflow(workflowAggregate);

  }

  @Override
  public A completeTask(
      final A workflowAggregate,
      final String taskId) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.completeTask(workflowAggregate, taskId);

  }

  @Override
  public A cancelTask(
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.cancelTask(workflowAggregate, taskId, bpmnErrorCode);

  }

  @Override
  public A completeUserTask(
      final A workflowAggregate,
      final String taskId) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.completeUserTask(workflowAggregate, taskId);

  }

  @Override
  public A cancelUserTask(
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.cancelUserTask(workflowAggregate, taskId, bpmnErrorCode);

  }

  @Override
  public void sendSignal(
      final String signalName) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionExceptionForSignal();
    }

    migrationProcessService.sendSignal(signalName);

  }

  @Override
  public A correlateMessage(
      final A workflowAggregate,
      final String messageName) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.correlateMessage(workflowAggregate, messageName, null);

  }

  @Override
  public A correlateMessage(
      final A workflowAggregate,
      final String messageName,
      final String correlationId) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.correlateMessage(workflowAggregate, messageName, correlationId);

  }

  @Override
  public A startWorkflowByMessage(
      final A workflowAggregate,
      final String messageName) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.startWorkflowByMessage(workflowAggregate, messageName);

  }

  /**
   * The viewer/history API is READ-ONLY: no transaction is required (nothing is
   * persisted, the aggregate is only asked for its ID) - see
   * {@link MigrationProcessService#getProcessDefinitions(Object, String)}.
   */
  @Override
  public List<io.vanillabp.spi.process.ProcessDefinition> getProcessDefinitions(
      final A workflowAggregate,
      final String historyContext) {

    return migrationProcessService.getProcessDefinitions(workflowAggregate, historyContext);

  }

  @Override
  public java.io.InputStream getBpmnXml(
      final String processDefinitionId) {

    return migrationProcessService.getBpmnXml(processDefinitionId);

  }

  @Override
  public io.vanillabp.spi.process.WorkflowHistory getWorkflowHistory(
      final A workflowAggregate,
      final String historyContext) {

    return migrationProcessService.getWorkflowHistory(workflowAggregate, historyContext);

  }

  public boolean transactionIsActive() {

    return !noTransactionIsActive();

  }

  private boolean noTransactionIsActive() {

    return !TransactionSynchronizationManager.isActualTransactionActive();

  }

}
