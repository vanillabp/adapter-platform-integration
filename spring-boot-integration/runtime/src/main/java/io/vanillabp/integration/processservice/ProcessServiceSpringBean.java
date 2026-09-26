package io.vanillabp.integration.processservice;

import java.io.InputStream;
import java.util.Collection;
import java.util.List;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.processservice.ProcessServiceBase;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.migration.processservice.TransactionRunnerResolver;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import io.vanillabp.spi.process.ProcessDefinition;
import io.vanillabp.spi.process.WorkflowHistory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * The bean an application injects as {@link io.vanillabp.spi.process.ProcessService}, one
 * per workflow aggregate class. It is registered by {@link ProcessServiceBeanRegistrar};
 * an application never builds one itself and does not name this class anywhere.
 * <p>
 * Almost nothing happens here. Every operation is handed to the
 * {@link MigrationProcessService} this bean holds, which is platform-neutral and does the
 * work: it elects the adapter, asks its questions inside the caller's transaction (see
 * decision 3 in the repository's DECISIONS.md) and writes the outbox entry which acts
 * after the commit (see decision 2 in the repository's DECISIONS.md). The Quarkus
 * integration holds the same object and hands it the same calls.
 * <p>
 * What this class adds is what only Spring can answer. Before every operation which moves
 * a workflow it checks that a transaction is open, because the aggregate and the outbox
 * entry have to be written together. The question goes to the runner serving this
 * aggregate rather than to Spring, so an application storing its aggregates outside
 * Spring's transactions is not refused a call it can perfectly well make (see decision 11
 * in the repository's DECISIONS.md). The read-only viewer and history calls carry no such
 * check, because they persist nothing.
 * <p>
 * The other half is registration: the bean announces itself at the phase-two router while
 * it is built, so the entries written for its workflow module and BPMN process find their
 * way back here after the commit.
 *
 * @param <A> The workflow aggregate class this service serves
 */
@Slf4j
public class ProcessServiceSpringBean<A> extends ProcessServiceBase<A> {

  @Getter
  private final MigrationProcessService<A> migrationProcessService;

  /**
   * The process service of EVERY BPMN process id the workflow service classes of this
   * aggregate declare, the primary one first - set by the
   * {@link ProcessServiceBeanRegistrar}, which is the only place seeing all declaring classes
   * at once.
   * <p>
   * Everything configurable per workflow is configurable for a secondary or declared-only id
   * too (its prioritized adapters, its outbox, what its leftovers were persisted under), and
   * each of those services asks its own questions. So the startup validations run over this
   * list rather than over the primary service alone - the id a rename leaves behind is exactly
   * the one whose leftovers nobody would otherwise look at.
   */
  private List<MigrationProcessService<A>> processServicesOfDeclaredIds;

  /**
   * Creates the bean without a transaction-runner resolver - kept for tests; the bean
   * registrar always passes one. Without a resolver the transaction check falls back to
   * Spring's own answer.
   *
   * @param workflowModuleId The id of the workflow module this process belongs to
   * @param bpmnProcessId The primary BPMN process id, picked among the classes declaring
   *     this aggregate
   * @param workflowAggregateClass The aggregate class, which is also what the bean is
   *     injected by
   * @param properties The validated VanillaBP configuration of this application
   * @param aggregatePersistenceAware Loads and saves the aggregate, the most specific one
   *     the application brings for this class
   * @param migratableProcessServices The process service of every adapter this application
   *     loaded, in no particular order - which of them the election asks, and in which
   *     order, is decided by the configured prioritized adapters
   * @param phaseTwoOutboxResolver Says which outbox rides this aggregate's transaction
   * @param phaseTwoRouter Where this bean registers itself as the target of its own
   *     phase-two entries, or <code>null</code> where nothing dispatches
   * @param workflowAdapterCache Remembers which adapter a workflow was last seen at, so
   *     the election does not start at the top every time
   * @param taskDeliveryLogResolver Says which delivery log rides this aggregate's
   *     transaction
   */
  public ProcessServiceSpringBean(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceAware,
      final List<MigratableProcessService<A>> migratableProcessServices,
      final PhaseTwoOutboxResolver phaseTwoOutboxResolver,
      final PhaseTwoRouter phaseTwoRouter,
      final WorkflowAdapterCache workflowAdapterCache,
      final TaskDeliveryLogResolver taskDeliveryLogResolver) {

    this(
        workflowModuleId, bpmnProcessId, workflowAggregateClass, properties, aggregatePersistenceAware, migratableProcessServices, phaseTwoOutboxResolver, phaseTwoRouter, workflowAdapterCache, taskDeliveryLogResolver, null);

  }

  /**
   * The constructor the bean registrar calls, from the lazy supplier of the bean. Every
   * collaborator is resolved at that moment rather than while the bean definitions are
   * read, so neither the persistence infrastructure nor an adapter is created too early.
   * <p>
   * The bean registers itself at the router here, which is why the router is a parameter
   * and not something this bean looks up later.
   *
   * @param workflowModuleId The id of the workflow module this process belongs to
   * @param bpmnProcessId The primary BPMN process id, picked among the classes declaring
   *     this aggregate
   * @param workflowAggregateClass The aggregate class, which is also what the bean is
   *     injected by
   * @param properties The validated VanillaBP configuration of this application
   * @param aggregatePersistenceAware Loads and saves the aggregate, the most specific one
   *     the application brings for this class
   * @param migratableProcessServices The process service of every adapter this application
   *     loaded, in no particular order - which of them the election asks, and in which
   *     order, is decided by the configured prioritized adapters
   * @param phaseTwoOutboxResolver Says which outbox rides this aggregate's transaction
   * @param phaseTwoRouter Where this bean registers itself as the target of its own
   *     phase-two entries, or <code>null</code> where nothing dispatches
   * @param workflowAdapterCache Remembers which adapter a workflow was last seen at, so
   *     the election does not start at the top every time
   * @param taskDeliveryLogResolver Says which delivery log rides this aggregate's
   *     transaction
   * @param transactionRunnerResolver Says which transaction this aggregate is written
   *     through, and is what the transaction check asks (see decision 11 in the
   *     repository's DECISIONS.md)
   */
  public ProcessServiceSpringBean(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceAware,
      final List<MigratableProcessService<A>> migratableProcessServices,
      final PhaseTwoOutboxResolver phaseTwoOutboxResolver,
      final PhaseTwoRouter phaseTwoRouter,
      final WorkflowAdapterCache workflowAdapterCache,
      final TaskDeliveryLogResolver taskDeliveryLogResolver,
      final TransactionRunnerResolver transactionRunnerResolver) {

    migrationProcessService = MigrationProcessService
        .<A>forBpmnProcess(workflowModuleId, bpmnProcessId, workflowAggregateClass)
        .properties(properties)
        .aggregatePersistence(aggregatePersistenceAware)
        .processServices(migratableProcessServices)
        .phaseTwoOutboxResolver(phaseTwoOutboxResolver)
        .workflowAdapterCache(workflowAdapterCache)
        .taskDeliveryLogResolver(taskDeliveryLogResolver)
        .transactionRunnerResolver(transactionRunnerResolver)
        .build();

    // register as phase-two dispatch target: outbox entries for this workflow
    // module/BPMN process are routed here after the local transaction was committed
    if (phaseTwoRouter != null) {
      phaseTwoRouter.register(migrationProcessService);
    }

  }

  /**
   * Hands this bean the services of the ids it does not serve itself. Called by
   * {@link ProcessServiceBeanRegistrar} while it builds the bean, because that is the only
   * place which sees all classes declaring this aggregate at once. An empty collection
   * leaves the bean answering with its primary service alone.
   *
   * @param processServicesOfDeclaredIds The process services of every declared BPMN process
   *          id, the primary one first
   */
  public void setProcessServicesOfDeclaredIds(
      final Collection<MigrationProcessService<A>> processServicesOfDeclaredIds) {

    this.processServicesOfDeclaredIds = (processServicesOfDeclaredIds == null) || processServicesOfDeclaredIds.isEmpty()
        ? null
        : List.copyOf(processServicesOfDeclaredIds);

  }

  /**
   * What the startup validations walk. They run over every declared id rather than over
   * the primary one alone, because the id a rename left behind is exactly the one whose
   * leftovers nobody would otherwise look at.
   *
   * @return The process services of every declared BPMN process id, the primary one first;
   *         the primary one alone where nothing was set (a test constructing this bean)
   */
  public List<MigrationProcessService<A>> getProcessServicesOfDeclaredIds() {

    return processServicesOfDeclaredIds != null
        ? processServicesOfDeclaredIds
        : List.of(migrationProcessService);

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

  /**
   * The primary BPMN process id of this aggregate: the process
   * {@link #startWorkflow(Object)} starts. A workflow service may declare more ids, and
   * those are served through {@link #getProcessServicesOfDeclaredIds()} rather than here.
   *
   * @return The BPMN process id, in its plain form - the scoped form a BPMS sees is built
   *     at the adapter boundary and never reaches this bean
   */
  public String getBpmnProcessId() {

    return migrationProcessService.getBpmnProcessId();

  }

  /**
   * The aggregate class this bean serves. It is what the bean is injected by, so it is
   * also how anything holding a list of process services picks the right one.
   *
   * @return The workflow aggregate class
   */
  public Class<A> getWorkflowAggregateClass() {

    return migrationProcessService.getWorkflowAggregateClass();

  }

  @Override
  public A startWorkflow(
      final A workflowAggregate) {

    if (noTransactionIsActive()) {
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
  public A aggregateChanged(
      final A workflowAggregate) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }

    return migrationProcessService.aggregateChanged(workflowAggregate, null);

  }

  @Override
  public A aggregateChanged(
      final A workflowAggregate,
      final String taskId) {

    if (noTransactionIsActive()) {
      throw newMissingTransactionException();
    }
    if ((taskId == null) || taskId.isBlank()) {
      throw new IllegalArgumentException(
          """
              No task-id given! Use aggregateChanged(aggregate) to push the aggregate at the \
              workflow's global scope, or pass the task-id reported to the @TaskId parameter of the \
              task whose scope should receive the values.""");
    }

    return migrationProcessService.aggregateChanged(workflowAggregate, taskId);

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
  public List<ProcessDefinition> getProcessDefinitions(
      final A workflowAggregate,
      final String historyContext) {

    return migrationProcessService.getProcessDefinitions(workflowAggregate, historyContext);

  }

  @Override
  public InputStream getBpmnXml(
      final String processDefinitionId) {

    return migrationProcessService.getBpmnXml(processDefinitionId);

  }

  @Override
  public WorkflowHistory getWorkflowHistory(
      final A workflowAggregate,
      final String historyContext) {

    return migrationProcessService.getWorkflowHistory(workflowAggregate, historyContext);

  }

  /**
   * Whether something is open the aggregate could be persisted in - the same question
   * every operation of this bean asks itself before it does anything. The answer comes
   * from the runner serving this aggregate and not from Spring, so an application with a
   * unit of work of its own is asked about its own (see decision 11 in the repository's
   * DECISIONS.md).
   *
   * @return Whether a transaction is open for this aggregate
   */
  public boolean transactionIsActive() {

    return !noTransactionIsActive();

  }

  /**
   * Whether nothing is open the aggregate could be persisted in. The question goes to the
   * runner serving this aggregate: an application storing its aggregates in a
   * system Spring does not manage has its own unit of work, and Spring's answer would be
   * wrong for it. Without a resolver (tests) Spring answers.
   */
  private boolean noTransactionIsActive() {

    final var runner = migrationProcessService.getTransactionRunner(null);
    return runner != null
        ? !runner.isTransactionActive()
        : !TransactionSynchronizationManager.isActualTransactionActive();

  }

}
