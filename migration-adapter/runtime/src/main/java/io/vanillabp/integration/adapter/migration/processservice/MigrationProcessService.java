package io.vanillabp.integration.adapter.migration.processservice;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.workflowtask.TaskDeliveryKey;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskHandler;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.Election;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import io.vanillabp.spi.service.TaskException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

/**
 * What every {@code ProcessService} call of an application ends up in, one instance per workflow
 * module and BPMN process: it saves the workflow aggregate, elects the adapter which holds the
 * workflow, runs the part of the operation which may run before the caller commits, and hands the
 * rest to the phase-two outbox.
 * <p>
 * The order the methods below share is the order of the guarantees. The aggregate is saved in the
 * caller's transaction, so an operation which the application rolls back leaves no trace anywhere.
 * What follows only ASKS the BPMS, which is what keeps a guiding error synchronous, thrown where
 * the application made the call (decision 3 in the repository's DECISIONS.md). What changes the
 * BPMS is planned as an outbox entry and executed after the commit
 * (decision 2 in the repository's DECISIONS.md), which is why
 * every one of those steps has to survive being executed twice.
 * <p>
 * Two more rules of this class are written down because other places rely on them: a failure of
 * phase two is classified by the adapter and judged here
 * (decision 12 in the repository's DECISIONS.md), and the transaction the work runs in is the one
 * resolved for THIS aggregate rather than the platform's
 * (decision 11 in the repository's DECISIONS.md).
 */
@Slf4j
// see decision 1 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public class MigrationProcessService<A> {

  @Getter
  private final String workflowModuleId;

  @Getter
  private final String bpmnProcessId;

  @Getter
  private final Class<A> workflowAggregateClass;

  /**
   * Map of known adapters. The key is the adapter id, the value is the adapter type.
   */
  @Getter
  private final Map<String, String> adapters;

  /**
   * List of adapter ids sorted by priority.
   */
  @Getter
  private final List<String> prioritizedAdapters;

  private final List<MigratableProcessService<A>> adapterProcessServices;

  /**
   * The process services of every adapter the workflow module is DEPLOYED to, which
   * is more than the prioritized adapters of this process
   * whenever another workflow of the module elects a different BPMS. A broadcast
   * signal goes to all of them: while a migration runs, workflows waiting for the
   * signal legitimately live in more than one BPMS.
   */
  private final List<MigratableProcessService<A>> deploymentAdapterProcessServices;

  private final AggregatePersistenceAware<A> aggregatePersistenceSupport;

  /**
   * The type of the aggregate's ID property or <code>null</code> if not determinable
   * (custom persistence owning the serialized form). Determined once at construction
   * and validated to round-trip losslessly through the outbox's String serialization
   * (see {@link AggregateIdRoundTrip}).
   */
  private final Class<?> aggregateIdType;

  /**
   * Resolves the outbox used to schedule phase two of every outbound operation.
   * Provided by the platform integration; may be <code>null</code> in tests -
   * {@link #validatePhaseTwoOutboxAtStartup()} fails the startup with a guiding
   * message when no outbox can be resolved.
   */
  private final PhaseTwoOutboxResolver phaseTwoOutboxResolver;

  /**
   * The outbox resolved for this process service's aggregate,
   * <code>null</code> until resolved (at startup via
   * {@link #validatePhaseTwoOutboxAtStartup()} or lazily as backstop).
   */
  private volatile PhaseTwoOutbox phaseTwoOutbox;

  /**
   * The election for operations on existing workflows (see
   * {@link WorkflowLocator}).
   */
  private final WorkflowLocator workflowLocator;

  /**
   * The bound <code>vanillabp.*</code> tree - kept because adapter-scoped properties
   * are resolved per DELIVERY (workflow module &gt; workflow &gt; task, see
   * {@link MigrationAdapterProperties#resolveForAdapter}).
   */
  private final MigrationAdapterProperties properties;

  /**
   * Resolves the log of processed task deliveries used for this aggregate. Provided by
   * the platform integration; may be <code>null</code> (tests) - deliveries are then
   * not deduplicated, which is the behaviour of every VanillaBP before this existed.
   */
  private final TaskDeliveryLogResolver taskDeliveryLogResolver;

  /**
   * The delivery log resolved for this process service's aggregate,
   * <code>null</code> until resolved (at startup via
   * {@link #validateTaskDeliveryLogAtStartup()} or lazily as backstop).
   */
  private volatile TaskDeliveryLog taskDeliveryLog;

  /**
   * Whether the "deliveries are not deduplicated" message was logged already - it
   * names a configuration gap, and one line per delivery would bury it.
   */
  private final java.util.concurrent.atomic.AtomicBoolean missingDeliveryLogReported = new java.util.concurrent.atomic.AtomicBoolean();

  /**
   * Resolves the transaction VanillaBP runs the work on this aggregate in.
   * Provided by the platform integration; may be <code>null</code> in tests and for
   * adapters handing their runner in directly - the runner passed by the caller is
   * used then.
   */
  private final TransactionRunnerResolver transactionRunnerResolver;

  /**
   * The runner resolved for this process service's aggregate, <code>null</code> until
   * resolved (at startup via {@link #validateTransactionRunnerAtStartup()} or lazily
   * as backstop).
   */
  private volatile TransactionRunner transactionRunner;

  /**
   * What the application counts about its deliveries. Handed in by the
   * platform integration after construction, because it exists once per application
   * while process services exist per BPMN process;
   * {@link io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics#NONE}
   * until then and for an application without a metrics backend.
   */
  private volatile io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics metrics = io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.NONE;

  /**
   * @param metrics What to count deliveries into, never <code>null</code>
   */
  public void setMetrics(
      final io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics metrics) {

    this.metrics = metrics == null
        ? io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.NONE
        : metrics;

  }

  /**
   * Creates a process service without an adapter cache (elections probe every
   * time) - kept for tests; the platform integrations always pass the cache.
   */
  public MigrationProcessService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceSupport,
      final List<MigratableProcessService<A>> processServices,
      final PhaseTwoOutboxResolver phaseTwoOutboxResolver) {

    this(
        workflowModuleId, bpmnProcessId, workflowAggregateClass, properties, aggregatePersistenceSupport, processServices, phaseTwoOutboxResolver, null);

  }

  /**
   * Creates a process service without a delivery-log resolver (deliveries are not
   * deduplicated) - kept for tests; the platform integrations always pass one.
   */
  public MigrationProcessService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceSupport,
      final List<MigratableProcessService<A>> processServices,
      final PhaseTwoOutboxResolver phaseTwoOutboxResolver,
      final WorkflowAdapterCache workflowAdapterCache) {

    this(
        workflowModuleId, bpmnProcessId, workflowAggregateClass, properties, aggregatePersistenceSupport, processServices, phaseTwoOutboxResolver, workflowAdapterCache, null);

  }

  /**
   * Creates a process service without a transaction-runner resolver - kept for tests
   * and for callers handing the runner in directly; the platform integrations always
   * pass one.
   */
  public MigrationProcessService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceSupport,
      final List<MigratableProcessService<A>> processServices,
      final PhaseTwoOutboxResolver phaseTwoOutboxResolver,
      final WorkflowAdapterCache workflowAdapterCache,
      final TaskDeliveryLogResolver taskDeliveryLogResolver) {

    this(
        workflowModuleId, bpmnProcessId, workflowAggregateClass, properties, aggregatePersistenceSupport, processServices, phaseTwoOutboxResolver, workflowAdapterCache, taskDeliveryLogResolver, null);

  }

  public MigrationProcessService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceSupport,
      final List<MigratableProcessService<A>> processServices,
      final PhaseTwoOutboxResolver phaseTwoOutboxResolver,
      final WorkflowAdapterCache workflowAdapterCache,
      final TaskDeliveryLogResolver taskDeliveryLogResolver,
      final TransactionRunnerResolver transactionRunnerResolver) {

    this.transactionRunnerResolver = transactionRunnerResolver;
    this.properties = properties;
    this.taskDeliveryLogResolver = taskDeliveryLogResolver;
    this.workflowModuleId = workflowModuleId;
    this.bpmnProcessId = bpmnProcessId;
    this.workflowAggregateClass = workflowAggregateClass;
    this.adapters = properties.adapterTypes();
    this.prioritizedAdapters = properties.getPrioritizedAdaptersFor(workflowModuleId, bpmnProcessId);
    this.aggregatePersistenceSupport = aggregatePersistenceSupport;
    // fail fast: EVERY prioritized adapter id must have a matching process
    // service - silently dropping one would make workflows start in the wrong
    // BPMS (exactly the error VanillaBP is meant to prevent)
    this.adapterProcessServices = prioritizedAdapters
        .stream()
        .map(adapterId -> processServices
            .stream()
            .filter(processService -> processService.getAdapterId().equals(adapterId))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                """
                    No VanillaBP adapter serves the prioritized adapter id '%s' configured for BPMN \
                    process '%s' of workflow module '%s'! Likely causes: the adapter's dependency \
                    is missing on the classpath, the adapter id is a typo in \
                    'vanillabp.prioritized-adapters' (or its overrides \
                    'vanillabp.workflow-modules.%s.prioritized-adapters' / \
                    'vanillabp.workflow-modules.%s.workflows.%s.prioritized-adapters'), or the \
                    adapter serves a different adapter id than configured."""
                    .formatted(
                        adapterId,
                        bpmnProcessId,
                        workflowModuleId,
                        workflowModuleId,
                        workflowModuleId,
                        bpmnProcessId))))
        .toList();
    this.deploymentAdapterProcessServices = properties
        .getDeploymentAdaptersFor(workflowModuleId)
        .stream()
        .map(adapterId -> processServices
            .stream()
            .filter(processService -> processService.getAdapterId().equals(adapterId))
            .findFirst()
            .orElse(null))
        .filter(java.util.Objects::nonNull)
        .toList();
    this.phaseTwoOutboxResolver = phaseTwoOutboxResolver;

    // startup check: the aggregate's ID has to round-trip losslessly through the
    // outbox's String serialization (fails with a guiding message otherwise); a
    // null ID type means a custom persistence layer owns the serialized form
    this.aggregateIdType = aggregatePersistenceSupport.getAggregateIdType();
    AggregateIdRoundTrip.validateIdTypeConvertible(workflowAggregateClass, aggregateIdType);

    this.workflowLocator = new WorkflowLocator(workflowModuleId, bpmnProcessId, workflowAdapterCache);

  }

  /**
   * Validates AT STARTUP that an outbox is available - a configuration defect must not
   * surface first at runtime, and every adapter needs one, because phase two of every
   * outbound operation is dispatched through it (decision 26 in the repository's
   * DECISIONS.md). Called by the platform integration once the application context is
   * ready (not mid-bean-construction, so no persistence infrastructure is materialized
   * early).
   *
   * @throws IllegalStateException If no outbox can be resolved, naming the remedies
   */
  public void validatePhaseTwoOutboxAtStartup() {

    if (resolvePhaseTwoOutbox() == null) {
      throw new IllegalStateException(
          buildNoOutboxMessage(
              adapterProcessServices
                  .getFirst()
                  .getAdapterId()));
    }

  }

  /**
   * Validates AFTER THE DEPLOYMENT that every prioritized adapter of this BPMN process
   * can locate workflows, and refuses a combination in which one of them cannot.
   * <p>
   * An adapter which cannot ask its BPMS answers the election optimistically, which is
   * right while it is the only BPMS configured and a guess as soon as it is not: the
   * walk stops at the first <code>ACTIVE</code>, so the guessing adapter takes the
   * operations of every adapter behind it in the list. That is a migration which
   * silently routes half of its workflows to the wrong BPMS.
   * <p>
   * This runs after the deployment rather than with the other startup validations,
   * because an adapter may only learn what its BPMS can do while it deploys - Camunda 8
   * finds out about the query API from the first query which fails. It still runs before
   * workflow processing starts, so nothing has touched a workflow when the message
   * arrives.
   *
   * @throws IllegalStateException If an adapter has to guess and the module does not
   *           accept it
   */
  public void validateElectionCapabilityAfterDeployment() {

    if (adapterProcessServices.size() < 2) {
      // nothing to guess between: whatever the single adapter answers, it is the only
      // BPMS which could hold the workflow
      return;
    }

    final var guessing = adapterProcessServices
        .stream()
        .filter(adapter -> !adapter.canLocateWorkflows())
        .map(MigratableProcessService::getAdapterId)
        .toList();
    if (guessing.isEmpty()) {
      return;
    }

    final var message = """
        The adapter(s) %s cannot ask their BPMS whether it holds a workflow, and BPMN process '%s' \
        of workflow module '%s' is served by %s adapters (%s)! Such an adapter answers the BPMS \
        election optimistically, the election stops at the first adapter saying yes, and the \
        operations of every adapter behind it in the list end up in the wrong BPMS - a migration \
        which loses half of its workflows without saying so. To solve this either
        - give that BPMS what it needs to answer (Camunda 8: configure secondary storage / the \
        query API; the Process-Engine-API has no query API at all, so it cannot be part of a \
        migration setup), or
        - prioritize exactly one adapter for this workflow module, or
        - accept the routing by list order with '%s: ACCEPTED' (or globally, \
        'vanillabp.election.guessing-adapters: ACCEPTED') - the message stays as a WARN."""
        .formatted(
            guessing,
            bpmnProcessId,
            workflowModuleId,
            adapterProcessServices.size(),
            prioritizedAdapters,
            MigrationAdapterProperties.guessingAdaptersProperty(workflowModuleId));

    if (properties.acceptsGuessingAdapters(workflowModuleId)) {
      log.warn("{}", message);
      return;
    }
    throw new IllegalStateException(message);

  }

  /**
   * The transaction runner serving this process service's aggregate: the most specific
   * {@link io.vanillabp.integration.spi.TransactionRunnerAware} bean, a
   * {@link TransactionRunner} bean of the application, or the platform's own runner.
   * Resolved once and cached - a resolution per delivery would ask the
   * bean container on every task.
   *
   * @param fallback The runner the caller would use, taken where no resolver was
   *          provided or the resolver knows nothing better (adapters and tests handing
   *          their runner in directly)
   * @return The runner to run work on this aggregate in
   */
  public TransactionRunner getTransactionRunner(
      final TransactionRunner fallback) {

    if (transactionRunnerResolver == null) {
      return fallback;
    }
    if (transactionRunner == null) {
      transactionRunner = transactionRunnerResolver.resolveFor(workflowAggregateClass);
    }
    return transactionRunner != null
        ? transactionRunner
        : fallback;

  }

  /**
   * Validates AT STARTUP that the work VanillaBP does on this aggregate has a
   * transaction to run in, and reports what that transaction covers.
   * <p>
   * Three outcomes. No runner at all ends the boot: such an application cannot start a
   * single workflow (the aggregate and the outbox entry have to be written in one
   * transaction), so booting green would only move the failure to the first workflow.
   * A store the platform can tell is not covered gets a WARN naming what is given up. A
   * combination the platform can name a fix for ends the boot as well, unless the
   * application accepts unguarded writes
   * (<code>vanillabp.transactions.unguarded-aggregate-writes</code>) - the message is
   * then logged as a WARN, because a decision like this has to stay visible.
   *
   * @throws IllegalStateException If no runner is available, or the coverage cannot work
   *           and unguarded writes are not accepted
   */
  public void validateTransactionRunnerAtStartup() {

    if (transactionRunnerResolver == null) {
      return;
    }
    final var runner = getTransactionRunner(null);
    if (runner == null) {
      throw new IllegalStateException(buildNoTransactionRunnerMessage());
    }
    log.info(
        "Workflow aggregate '{}' (BPMN process '{}' of workflow module '{}') is processed in the "
            + "transaction of: {}",
        workflowAggregateClass.getName(),
        bpmnProcessId,
        workflowModuleId,
        transactionRunnerResolver.describeResolutionFor(workflowAggregateClass));

    final var coverage = transactionRunnerResolver.coverageOf(workflowAggregateClass);
    switch (coverage.verdict()) {
      case COVERED, UNKNOWN -> {
      }
      case UNGUARDED -> log.warn("{}", coverage.message());
      case UNCOVERABLE -> {
        if (properties.acceptsUnguardedAggregateWrites(workflowModuleId)) {
          log.warn(
              "{} This was accepted by setting '{}' - VanillaBP does not stop the application, and "
                  + "the guarantees named above are the ones you have.",
              coverage.message(),
              MigrationAdapterProperties.unguardedAggregateWritesProperty(workflowModuleId));
        } else {
          throw new IllegalStateException(
              """
                  %s
                  If this is what you want, state it by setting '%s' to 'accepted' (or \
                  '%s.transactions.unguarded-aggregate-writes' for the whole application) - the \
                  message stays as a warning then."""
                  .formatted(
                      coverage.message(),
                      MigrationAdapterProperties.unguardedAggregateWritesProperty(workflowModuleId),
                      MigrationAdapterProperties.PREFIX));
        }
      }
    }

  }

  /**
   * The guiding message of a workflow aggregate nothing can open a transaction for.
   */
  private String buildNoTransactionRunnerMessage() {

    return """
        No transaction is available to process workflows of BPMN process '%s' (workflow module \
        '%s', workflow aggregate '%s')! VanillaBP loads the workflow aggregate, invokes the \
        @WorkflowTask method and saves the aggregate within ONE transaction, and nothing provides \
        one. To solve this either
        %s
        - define a bean implementing io.vanillabp.integration.spi.TransactionRunner, which serves \
        every workflow aggregate of this application, or
        - define a bean implementing io.vanillabp.integration.spi.TransactionRunnerAware to \
        provide a runner for this aggregate (or for an interface all your aggregates \
        implement)."""
        .formatted(
            bpmnProcessId,
            workflowModuleId,
            workflowAggregateClass.getName(),
            transactionRunnerResolver.remediesDescription());

  }

  /**
   * Converts the serialized (String) workflow-aggregate ID of an outbox entry back
   * into the aggregate's ID type. If the ID type is not determinable (custom
   * persistence), the String is passed through unchanged - the custom persistence
   * layer is responsible for handling the serialized form.
   *
   * @param serializedAggregateId The aggregate ID in serialized form
   * @return The aggregate ID in the aggregate's ID type
   */
  public Object convertAggregateId(
      final String serializedAggregateId) {

    return AggregateIdRoundTrip.convert(serializedAggregateId, aggregateIdType);

  }

  /**
   * The name of the aggregate's ID property (see
   * {@link AggregatePersistenceAware#getAggregateIdName()}) - remote BPMS store
   * the aggregate's ID as a process variable of this name.
   *
   * @return The ID property's name
   */
  public String getAggregateIdName() {

    return aggregatePersistenceSupport.getAggregateIdName();

  }

  /**
   * Loads the workflow aggregate by its serialized ID within the CALLER's
   * transaction - used to resolve aggregate attributes referenced by BPMN
   * expressions of embedded BPMS (the expression evaluates inside an engine
   * transaction the aggregate has to join).
   *
   * @param serializedAggregateId The aggregate ID in serialized form
   * @return The aggregate or <code>null</code>
   */
  public A loadWorkflowAggregate(
      final String serializedAggregateId) {

    return aggregatePersistenceSupport.loadById(convertAggregateId(serializedAggregateId));

  }

  /**
   * The type of the workflow aggregate's ID attribute, or <code>null</code> if the
   * persistence layer does not report one (it then owns the serialized form).
   *
   * @return The ID type or <code>null</code>
   */
  public Class<?> getAggregateIdType() {

    return aggregateIdType;

  }

  /**
   * Loads the workflow aggregate by its ID in the aggregate's own ID type - used
   * where the ID was not serialized in the first place (a workflow the BPMS started
   * on its own).
   *
   * @param workflowAggregateId The ID in the aggregate's ID type
   * @return The aggregate or <code>null</code> if there is none
   */
  public A loadWorkflowAggregateById(
      final Object workflowAggregateId) {

    return aggregatePersistenceSupport.loadById(workflowAggregateId);

  }

  /**
   * @param workflowAggregate The aggregate to persist
   * @return The persisted aggregate (attached, in case of an ORM)
   */
  public A saveWorkflowAggregate(
      final A workflowAggregate) {

    return aggregatePersistenceSupport.save(workflowAggregate);

  }

  /**
   * @param workflowAggregate The aggregate to investigate
   * @return Its ID
   */
  public Object getWorkflowAggregateId(
      final A workflowAggregate) {

    return aggregatePersistenceSupport.getAggregateId(workflowAggregate);

  }

  /**
   * Processes a BPMN task: loads the workflow aggregate by the context's serialized
   * ID, invokes the given <code>&#64;WorkflowTask</code> handler and saves the
   * aggregate - all within one transaction run by the given
   * {@link TransactionRunner} (a new transaction, or the caller's if
   * {@link TaskInvocationContext#runInCurrentTransaction()}).
   * <p>
   * The three outcomes of the restored V1 contract:
   * <ul>
   * <li>normal return - aggregate saved, transaction commits;
   * {@link WorkflowTaskOutcome.Kind#COMPLETED} (or
   * {@link WorkflowTaskOutcome.Kind#COMPLETION_PENDING} for methods declaring a
   * <code>&#64;TaskId</code> parameter).</li>
   * <li>{@link TaskException} - aggregate saved anyway, transaction COMMITS,
   * {@link WorkflowTaskOutcome.Kind#BPMN_ERROR} carries the error code for
   * error-boundary routing.</li>
   * <li>any other exception - propagates out of the transactional work, the
   * transaction rolls back, the exception reaches the adapter: the task is not
   * completed and BPMS retry semantics apply.</li>
   * </ul>
   *
   * @param handler The handler resolved by the
   *          {@link io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry}
   * @param context The invocation context supplied by the adapter
   * @param platformTransactionRunner The platform's transaction runner, used unless the
   *          application contributed one for this aggregate
   * @param rollbackRuleRemedies How a rollback rule excluding a {@link TaskException} is
   *          written on this platform, named by the failure of the rollback-only check
   * @return The outcome the adapter maps to the BPMS
   */
  public WorkflowTaskOutcome executeWorkflowTask(
      final WorkflowTaskHandler handler,
      final TaskInvocationContext context,
      final TransactionRunner platformTransactionRunner,
      final List<String> rollbackRuleRemedies) {

    // Everything the application logs while its handler runs carries the
    // workflow it runs for, the delivery is counted and measured, and the running
    // activation is readable - here, because this is the one place every BPMS passes
    // through
    final var startedAt = System.nanoTime();
    WorkflowTaskOutcome outcome = null;
    try (var ignored = io.vanillabp.integration.adapter.migration.observability.DeliveryMdc
        .ofTaskDelivery(
            context.getAdapterId(),
            workflowModuleId,
            bpmnProcessId,
            context.getWorkflowAggregateId(),
            context.getTaskDefinition(),
            context.getDeliveryId());
         // what the application plans from inside this handler is planned by THIS
         // activation, which is what tells multi-instance siblings apart
         var activation = io.vanillabp.integration.spi.RunningActivation
             .of(context.getActivationId())) {
      outcome = deliverWorkflowTask(handler, context, platformTransactionRunner, rollbackRuleRemedies);
      return outcome;
    } finally {
      metrics
          .taskDelivered(
              context.getAdapterId(),
              workflowModuleId,
              bpmnProcessId,
              context.getTaskDefinition(),
              deliveryOutcomeOf(outcome),
              System.nanoTime() - startedAt);
    }

  }

  /**
   * How a delivery ended, as the metrics see it: a delivery which produced no outcome
   * threw, which means the transaction was rolled back and the BPMS gets the work
   * back.
   *
   * @param outcome The outcome or <code>null</code> if the delivery threw
   * @return The outcome to count
   */
  private static io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.DeliveryOutcome deliveryOutcomeOf(
      final WorkflowTaskOutcome outcome) {

    if (outcome == null) {
      return io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.DeliveryOutcome.FAILED;
    }
    return switch (outcome.kind()) {
      case COMPLETED ->
        io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.DeliveryOutcome.COMPLETED;
      case COMPLETION_PENDING ->
        io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.DeliveryOutcome.PENDING;
      case BPMN_ERROR ->
        io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.DeliveryOutcome.BPMN_ERROR;
    };

  }

  private WorkflowTaskOutcome deliverWorkflowTask(
      final WorkflowTaskHandler handler,
      final TaskInvocationContext context,
      final TransactionRunner platformTransactionRunner,
      final List<String> rollbackRuleRemedies) {

    // the runner of the application where it contributed one for this aggregate, the
    // platform's otherwise - resolved once and cached by the process service
    final var runner = getTransactionRunner(platformTransactionRunner);

    // a delivery proves which BPMS holds this workflow - recorded before anything
    // else, so it also holds for a delivery the handler does not subscribe to
    rememberWorkflowAdapter(context.getWorkflowAggregateId(), context.getAdapterId());

    // lifecycle-event filter: a delivery of an event the method does not
    // subscribe to (e.g. CANCELED to a method without a @TaskEvent parameter) is
    // skipped entirely - no transaction, no aggregate access, no side effects
    if (!handler.acceptsEvent(context.getTaskEvent())) {
      log.debug(
          "Skipping delivery of task event '{}' to '{}': the method does not subscribe to it",
          context.getTaskEvent(),
          handler.describe());
      return handler.isAsynchronousTask()
          ? WorkflowTaskOutcome.completionPending()
          : WorkflowTaskOutcome.completed();
    }

    // a BPMS repeating a delivery it never learned the result of must not run the
    // business code twice - what was processed is remembered, and a redelivery is
    // answered with the recorded outcome (leaving the task open instead would keep it
    // open forever)
    final var deliveryKey = deduplicateDeliveries(context.getAdapterId(), context.getTaskDefinition())
        ? TaskDeliveryKey.of(workflowModuleId, bpmnProcessId, context)
        : null;
    final var deliveryLog = deliveryKey == null
        ? null
        : resolveTaskDeliveryLog();
    if ((deliveryKey != null) && (deliveryLog == null)) {
      reportMissingDeliveryLog(context.getAdapterId());
    }

    final Supplier<WorkflowTaskOutcome> transactionalWork = () -> {
      if (deliveryLog != null) {
        final var recorded = deliveryLog
            .recordedDelivery(deliveryKey)
            .flatMap(delivery -> recordedOutcomeOf(deliveryLog, delivery, context));
        if (recorded.isPresent()) {
          log.info(
              "Skipping the repeated delivery of task '{}' (BPMN process '{}' of workflow module "
                  + "'{}', aggregate '{}'): it was processed before, reporting the recorded outcome "
                  + "{} again",
              context.getTaskDefinition(),
              bpmnProcessId,
              workflowModuleId,
              context.getWorkflowAggregateId(),
              recorded.get().kind());
          metrics
              .taskRedeliveryDeduplicated(
                  context.getAdapterId(),
                  workflowModuleId,
                  bpmnProcessId,
                  context.getTaskDefinition());
          return recorded.get();
        }
      }
      final var aggregateId = convertAggregateId(context.getWorkflowAggregateId());
      final var workflowAggregate = aggregatePersistenceSupport.loadById(aggregateId);
      if (workflowAggregate == null) {
        throw new IllegalStateException(
            """
                No workflow aggregate of class '%s' having the ID '%s' was found processing a task \
                of BPMN process '%s' of workflow module '%s'! The aggregate has a 1:1 relation to \
                the workflow - it must not be deleted while the workflow is active."""
                .formatted(
                    workflowAggregateClass.getName(),
                    context.getWorkflowAggregateId(),
                    bpmnProcessId,
                    workflowModuleId));
      }
      try {
        handler.invoke(workflowAggregate, context);
        aggregatePersistenceSupport.save(workflowAggregate);
        final var outcome = handler.isAsynchronousTask()
            ? WorkflowTaskOutcome.completionPending()
            : WorkflowTaskOutcome.completed();
        recordDelivery(deliveryLog, deliveryKey, context, outcome);
        failIfRollbackOnly(handler, context, runner, rollbackRuleRemedies);
        return outcome;
      } catch (final TaskException taskException) {
        // the restored V1 contract: a TaskException is a BPMN error, not a
        // failure - the aggregate changes are persisted and the transaction
        // commits (V1 applications used @Transactional(noRollbackFor =
        // TaskException.class) for exactly this)
        aggregatePersistenceSupport.save(workflowAggregate);
        final var outcome = WorkflowTaskOutcome
            .bpmnError(taskException.getErrorCode(), taskException.getErrorName());
        recordDelivery(deliveryLog, deliveryKey, context, outcome);
        failIfRollbackOnly(handler, context, runner, rollbackRuleRemedies);
        return outcome;
      }
    };

    return io.vanillabp.integration.adapter.migration.transaction.AggregateWrite
        .inTransaction(
            runner,
            context.runInCurrentTransaction(),
            workflowModuleId,
            bpmnProcessId,
            context.getWorkflowAggregateId(),
            "processing task '%s'".formatted(context.getTaskDefinition()),
            transactionalWork);

  }

  /**
   * Whether deliveries of the given adapter are deduplicated for the given task
   * (<code>vanillabp.adapters.&lt;id&gt;.deduplicate-deliveries</code>, resolvable per
   * workflow module, workflow and task). The default is <code>true</code>; an adapter
   * reporting no ID at all is not deduplicated, since neither the configuration nor
   * the delivery key could be attributed to a BPMS then.
   *
   * @param adapterId The ID of the adapter delivering the task or <code>null</code>
   * @param taskDefinition The task definition delivered
   * @return Whether to remember this delivery
   */
  private boolean deduplicateDeliveries(
      final String adapterId,
      final String taskDefinition) {

    if ((adapterId == null) || (properties == null)) {
      return false;
    }
    final var configured = properties
        .resolveForAdapter(
            workflowModuleId,
            bpmnProcessId,
            taskDefinition,
            adapterId,
            AdapterProperties::getDeduplicateDeliveries);
    return (configured == null) || configured;

  }

  /**
   * Whether an ended workflow of this BPMN process releases the records of its processed
   * task deliveries (<code>vanillabp.delivery.release-on-workflow-end</code>, overridable
   * per workflow module). Asked by the end-of-workflow path, and by the adapters through
   * {@link io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedInvoker#workflowEndedHandlerExists}
   * - where it is on, the end has to be reported even without an application handler.
   *
   * @return Whether the records are released when a workflow ends
   */
  public boolean releasesDeliveryRecordsOnWorkflowEnd() {

    return (properties != null) && properties.releasesDeliveryRecordsOnWorkflowEnd(workflowModuleId);

  }

  /**
   * Deletes the records of the processed task deliveries of ONE ended workflow. Invoked
   * within the transaction of the end notification (see
   * {@link io.vanillabp.integration.adapter.migration.workflowend.WorkflowEndedHandlers}),
   * so the deletion commits with it - and a notification whose transaction is rolled back
   * leaves the records where they were, to be released by the redelivered notification or
   * by the retention.
   *
   * @param workflowAggregateId The ID of the ended workflow's aggregate
   * @param recordedBefore Only records written before this moment are deleted - the bound
   *          which keeps the records of a SECOND workflow on the same aggregate
   * @return The number of records deleted
   */
  public int releaseDeliveryRecords(
      final String workflowAggregateId,
      final java.time.Instant recordedBefore) {

    final var deliveryLog = resolveTaskDeliveryLog();
    if (deliveryLog == null) {
      return 0;
    }
    final var released = deliveryLog
        .releaseRecordsOf(workflowModuleId, bpmnProcessId, workflowAggregateId, recordedBefore);
    log.debug(
        "Released {} task-delivery record(s) of the ended workflow '{}' (BPMN process '{}' of "
            + "workflow module '{}')",
        released,
        workflowAggregateId,
        bpmnProcessId,
        workflowModuleId);
    return released;

  }

  /**
   * Validates AT STARTUP that the store resolved for this aggregate can do what
   * <code>release-on-workflow-end</code> promises. A store which does not implement
   * {@link TaskDeliveryLog#releaseRecordsOf(String, String, String, java.time.Instant)}
   * keeps its records until the retention passed - which is not wrong, but it is not what
   * the application configured, so it is said once at startup naming the store and the
   * property.
   * <p>
   * Nothing happens where the release is switched off: an application which did not ask
   * for it must not be told about a method its store does not have.
   */
  private void validateDeliveryRecordReleaseAtStartup() {

    if (!releasesDeliveryRecordsOnWorkflowEnd()) {
      return;
    }
    final var deliveryLog = resolveTaskDeliveryLog();
    if (deliveryLog == null) {
      // no store at all means no records at all - there is nothing to release, and the
      // missing store is reported by the check for deduplication itself
      return;
    }
    final var storeClass = taskDeliveryLogResolver != null
        ? taskDeliveryLogResolver.storeClassOf(deliveryLog)
        : deliveryLog.getClass();
    if (implementsRelease(storeClass)) {
      return;
    }
    log.warn(
        """
            The TaskDeliveryLog '{}' does not implement 'releaseRecordsOf', but '{}' is switched on \
            for BPMN process '{}' of workflow module '{}' - the records of an ended workflow are \
            NOT deleted when it ends but once 'vanillabp.delivery.retention' passed. To solve this \
            either
            - implement io.vanillabp.integration.spi.TaskDeliveryLog#releaseRecordsOf in '{}', or
            - set '{}' to 'false' to state that the retention is what cleans up the records.""",
        storeClass.getName(),
        MigrationAdapterProperties.releaseOnWorkflowEndProperty(workflowModuleId),
        bpmnProcessId,
        workflowModuleId,
        storeClass.getName(),
        MigrationAdapterProperties.releaseOnWorkflowEndProperty(workflowModuleId));

  }

  /**
   * Whether the given store implements the release itself instead of inheriting the
   * default of {@link TaskDeliveryLog} which does nothing.
   *
   * @param storeClass The store's class, unwrapped by the platform integration
   * @return Whether the store releases records
   */
  private static boolean implementsRelease(
      final Class<?> storeClass) {

    try {
      // resolves to the override where there is one, and to the interface's default
      // method otherwise - which is exactly the question asked here
      final var method = storeClass
          .getMethod(
              "releaseRecordsOf",
              String.class,
              String.class,
              String.class,
              java.time.Instant.class);
      return method.getDeclaringClass() != TaskDeliveryLog.class;
    } catch (final NoSuchMethodException e) {
      // a store compiled against an older SPI: it cannot release either
      return false;
    }

  }

  /**
   * Reports AT STARTUP an adapter id which the persisted state still names although the
   * application does not configure it any more.
   * <p>
   * VanillaBP persists the adapter id twice: an outbox entry of a START operation names
   * the adapter elected in phase one, and the key of every delivery record is built from
   * the delivering adapter. An id which is gone from the configuration therefore has two
   * readings, and both cost the application something:
   * <ul>
   * <li>the id was RENAMED. Nothing serves the old name any more, so an outbox entry
   * fails, is repeated and is finally blocked - the aggregate was persisted and its
   * workflow never started - and a redelivery finds no record, so the
   * <code>&#64;WorkflowTask</code> method runs a second time;</li>
   * <li>the id was removed while entries or open tasks were still left, which is the last
   * step of a migration done one step too early.</li>
   * </ul>
   * Neither is reported by anything else until the entry is dispatched respectively the
   * task is delivered again, which is why this asks the stores instead of waiting. It
   * WARNS rather than failing the boot: the entries are not lost, they wait, and a boot
   * failure would stop the very application which is about to repair its configuration.
   * <code>vanillabp.retired-adapters</code> states that the leftovers are known and turns
   * the message into a DEBUG line.
   * <p>
   * Only stores which are already resolved are asked - this runs after the outbox and the
   * delivery-log validations, so an application which needs neither is not made to
   * materialize one for a question about it. A store which cannot answer (the SPI default,
   * an empty set) is not asked twice and nothing is invented.
   * <p>
   * Three questions per BPMN process, whatever the stores hold: each of them is a number or
   * a set of adapter ids the database reduced to, never the entries themselves. Decision 19
   * in the repository's DECISIONS.md says why a start may not be allowed to grow with what
   * an application has been through.
   */
  public void validatePersistedAdapterIdsAtStartup() {

    reportOpenTasksNobodyRemembers();
    reportUnconfiguredAdapterIds(
        phaseTwoOutbox == null
            ? java.util.Set.<String>of()
            : phaseTwoOutbox.adapterIdsOfPendingCalls(workflowModuleId, bpmnProcessId),
        "waiting phase-two outbox entries",
        "the workflow was persisted and never started");
    reportUnconfiguredAdapterIds(
        taskDeliveryLog == null
            ? java.util.Set.<String>of()
            : taskDeliveryLog.adapterIdsOfOpenTasks(workflowModuleId, bpmnProcessId),
        "records of tasks which are still open",
        "a redelivery runs the @WorkflowTask method a second time");

  }


  /**
   * Reports AT STARTUP that the BPMS holds tasks open which VanillaBP has no record of,
   * so their next delivery will run the <code>&#64;WorkflowTask</code> method again.
   *
   * <h2>What this catches</h2>
   *
   * The wiki promises that a repeated delivery does not run the handler a second time,
   * and that promise rests on a record written when the handler ran. A task which was
   * already open before this application first ran has none - which is exactly what an
   * upgrade from version 1 leaves behind, since version 1 kept no such records at all.
   * The window is real, it closes as those tasks are delivered once each, and without
   * this nobody can see it: no message is produced when it happens, because from the
   * core's point of view a delivery it has never seen is simply a new one.
   *
   * <h2>Why counting is all that is done</h2>
   *
   * Nothing can be adopted. A record would have to claim that the handler ran and left
   * the task open; a job which is activated and still there may equally be a handler
   * which crashed halfway, no BPMS can tell the two apart, and the wrong guess skips
   * business code which never ran. So the honest answer is the number plus the sentence
   * that the guards in the handlers still carry the case, which is what version 1 needed
   * anyway.
   *
   * <h2>When it stays silent</h2>
   *
   * Silent unless BOTH sides answer and both answers are interesting: a store which
   * cannot say whether it holds open records, a BPMS which cannot count its open tasks,
   * a store which DOES hold open records (an application which has been running), and a
   * BPMS holding nothing open (a fresh installation) each end it. So a normal restart
   * says nothing, a first start on an empty system says nothing, and the upgrade says
   * something once per BPMN process.
   */
  private void reportOpenTasksNobodyRemembers() {

    final var deliveryLog = taskDeliveryLog;
    if (deliveryLog == null) {
      return;
    }
    final var hasRecords = deliveryLog.hasOpenRecords(workflowModuleId, bpmnProcessId);
    if ((hasRecords == null) || hasRecords.booleanValue()) {
      return;
    }
    adapterProcessServices
        .stream()
        .filter(MigratableProcessService::deliversTasksAtLeastOnce)
        .forEach(adapter -> {
          final var open = adapter.openTaskCount(workflowModuleId, bpmnProcessId);
          if ((open == null) || (open == 0)) {
            return;
          }
          log
              .info(
                  """
                      Adapter '{}' holds {} open task(s) of BPMN process '{}' (workflow module '{}') \
                      which VanillaBP has no record of. It remembers a delivery from the moment your \
                      handler ran, so tasks which were already open before this application first ran \
                      are not in that memory: the next delivery of each of them runs the \
                      @WorkflowTask method a SECOND time, which is what happens without the record \
                      and what VanillaBP 1 did for every delivery. Nothing can be repaired here - a \
                      record would have to claim your handler ran, and an activated job which is \
                      still there may just as well be a handler which crashed halfway. So keep the \
                      guards in your handlers until this number is zero, which it becomes as each of \
                      those tasks is delivered once.""",
                  adapter.getAdapterId(),
                  open,
                  bpmnProcessId,
                  workflowModuleId);
        });

  }

  /**
   * Says once per adapter id and store what the persisted state names and the
   * configuration does not.
   *
   * @param persistedAdapterIds What the store answered
   * @param whatIsLeftOver How the leftovers are named in the message
   * @param whatItCosts What happens if nothing is done about it
   */
  private void reportUnconfiguredAdapterIds(
      final java.util.Set<String> persistedAdapterIds,
      final String whatIsLeftOver,
      final String whatItCosts) {

    if ((persistedAdapterIds == null) || persistedAdapterIds.isEmpty()) {
      return;
    }
    final var configuredAdapterIds = properties.adapterTypes().keySet();
    persistedAdapterIds
        .stream()
        .filter(java.util.Objects::nonNull)
        .filter(adapterId -> !configuredAdapterIds.contains(adapterId))
        .sorted()
        .forEach(adapterId -> {
          if (properties.getRetiredAdapters().contains(adapterId)) {
            log
                .debug(
                    "The adapter id '{}' is not configured any more and has {} of BPMN process '{}' "
                        + "(workflow module '{}'). Retired deliberately ('{}.retired-adapters').",
                    adapterId,
                    whatIsLeftOver,
                    bpmnProcessId,
                    workflowModuleId,
                    MigrationAdapterProperties.PREFIX);
            return;
          }
          log
              .warn(
                  """
                      The adapter id '{}' is NOT configured any more, but it still has {} of BPMN \
                      process '{}' (workflow module '{}'). VanillaBP persists the adapter id to know \
                      what belongs to which BPMS, so there are two readings and both cost you \
                      something: the id was RENAMED - then {} - or it was removed while its BPMS \
                      still had work left. To solve this either
                        - configure '{}.adapters.{}.*' again (a rename: put the old name back; a \
                      migration: keep the old BPMS until nothing of it is left), or
                        - state that you know about the leftovers: {}.retired-adapters: [{}]
                      An adapter id is a name to choose once: see the wiki, 'BPMS migration' - \
                      'Naming adapter ids, and never renaming them'.""",
                  adapterId,
                  whatIsLeftOver,
                  bpmnProcessId,
                  workflowModuleId,
                  whatItCosts,
                  MigrationAdapterProperties.PREFIX,
                  adapterId,
                  MigrationAdapterProperties.PREFIX,
                  adapterId);
        });

  }

  /**
   * Validates AT STARTUP that a delivery log is available if any prioritized adapter
   * may repeat a delivery
   * ({@link MigratableProcessService#deliversTasksAtLeastOnce()}) and deduplication is
   * switched on. Unlike the outbox this does NOT fail the boot: without a log
   * VanillaBP behaves exactly as it did before the feature existed (at-least-once, the
   * rule to key business decisions on the aggregate's state carries the case), so a
   * guiding WARN naming both remedies is the honest answer - and it is logged at
   * startup instead of surfacing per delivery.
   * <p>
   * Nothing is resolved where no adapter can repeat a delivery: an application using
   * an embedded BPMS only must not be pushed towards a store it does not need.
   */
  public void validateTaskDeliveryLogAtStartup() {

    validateDeliveryRecordReleaseAtStartup();

    final var atLeastOnceAdapters = adapterProcessServices
        .stream()
        .filter(MigratableProcessService::deliversTasksAtLeastOnce)
        .map(MigratableProcessService::getAdapterId)
        .filter(adapterId -> deduplicateDeliveries(adapterId, null))
        .toList();
    if (atLeastOnceAdapters.isEmpty()) {
      return;
    }
    if (resolveTaskDeliveryLog() == null) {
      reportMissingDeliveryLog(atLeastOnceAdapters.getFirst());
    }

  }

  private TaskDeliveryLog resolveTaskDeliveryLog() {

    if ((taskDeliveryLog == null) && (taskDeliveryLogResolver != null)) {
      taskDeliveryLog = taskDeliveryLogResolver.resolveFor(workflowAggregateClass);
    }
    return taskDeliveryLog;

  }

  /**
   * States once that deliveries of this BPMN process are not deduplicated, and how to
   * change that. Also the answer to "why did my handler run twice" - the message is
   * the first thing to look for then.
   *
   * @param adapterId The ID of an adapter which may repeat a delivery
   */
  private void reportMissingDeliveryLog(
      final String adapterId) {

    if (!missingDeliveryLogReported.compareAndSet(false, true)) {
      return;
    }
    log.warn(
        """
            Adapter '{}' may deliver a task of BPMN process '{}' of workflow module '{}' more than \
            once, but no TaskDeliveryLog is available for aggregate '{}' - a repeated delivery will \
            run the @WorkflowTask method again. To solve this either
            {}
            - define your own bean implementing io.vanillabp.integration.spi.TaskDeliveryLog \
            (assign it to specific aggregates via a io.vanillabp.integration.spi.TaskDeliveryLogAware \
            bean), or
            - set 'vanillabp.adapters.{}.deduplicate-deliveries' to 'false' to state that the \
            handlers of this application are idempotent themselves.""",
        adapterId,
        bpmnProcessId,
        workflowModuleId,
        workflowAggregateClass.getName(),
        taskDeliveryLogResolver == null
            ? "- provide a TaskDeliveryLogResolver (platform integration), or"
            : taskDeliveryLogResolver.remediesDescription(),
        adapterId);

  }

  /**
   * Remembers a processed delivery within the transaction which also persists the
   * aggregate - the two commit together or not at all.
   */
  private void recordDelivery(
      final TaskDeliveryLog deliveryLog,
      final String deliveryKey,
      final TaskInvocationContext context,
      final WorkflowTaskOutcome outcome) {

    if (deliveryLog == null) {
      return;
    }
    // the task travels with the record so the election of a later completion can read from
    // it which adapter holds that task, instead of asking every configured BPMS
    final var recordWasWritten = deliveryLog.record(
        new TaskDelivery(
            deliveryKey, context.getAdapterId(), workflowModuleId, bpmnProcessId, context
                .getWorkflowAggregateId(), context.getTaskDefinition(), context
                    .getTaskId(), outcome.kind().name(), outcome.errorCode(), outcome
                        .errorName(), java.time.Instant.now(), null));
    if (!recordWasWritten) {
      reportHandlerRanTwiceAtTheSameTime(deliveryKey, context);
    }

  }

  /**
   * Says that the handler of this task ran twice at the same time, which is the one case
   * a record written after the work cannot prevent: a delivery starting while another
   * one is still running finds no record yet, so both run and only the one committing
   * first gets its record written.
   * <p>
   * The delivery which lost is the only place where the overlap becomes visible at all,
   * so it is where it is said out loud. Nothing is rolled back: the record which stands
   * describes work which was really done, and this delivery's own work committed just as
   * well.
   */
  private void reportHandlerRanTwiceAtTheSameTime(
      final String deliveryKey,
      final TaskInvocationContext context) {

    log.warn(
        """
            Two deliveries of task '{}' (BPMN process '{}' of workflow module '{}', workflow \
            aggregate '{}') were processed at the SAME time: adapter '{}' handed the task out \
            again while the first handler was still running, so both found no record and the \
            @WorkflowTask method ran twice. The record written by the delivery which committed \
            first stands and this one added nothing to it (delivery key '{}'). Whatever the \
            handler did beside writing the workflow aggregate, sending a mail for instance, \
            happened twice. Key such decisions on the state of the workflow aggregate, and where \
            the BPMS lets you say how long a task stays locked, a lock which outlasts the handler \
            keeps the second delivery from being handed out at all.""",
        context.getTaskDefinition(),
        bpmnProcessId,
        workflowModuleId,
        context.getWorkflowAggregateId(),
        context.getAdapterId(),
        deliveryKey);
    metrics
        .taskRedeliveryRanConcurrently(
            context.getAdapterId(),
            workflowModuleId,
            bpmnProcessId,
            context.getTaskDefinition());

  }

  /**
   * The outcome a recorded delivery is answered with. An outcome the core does not
   * know (a record written by a newer version, or a store returning something of its
   * own) yields an empty result: the handler runs again, which is the behaviour without
   * any log at all, and the WARN says why.
   */
  private java.util.Optional<WorkflowTaskOutcome> recordedOutcomeOf(
      final TaskDeliveryLog deliveryLog,
      final TaskDelivery delivery,
      final TaskInvocationContext context) {

    try {
      return java.util.Optional
          .of(
              switch (WorkflowTaskOutcome.Kind.valueOf(delivery.outcome())) {
                case COMPLETED -> WorkflowTaskOutcome.completed();
                case COMPLETION_PENDING -> stillOpen(deliveryLog, delivery, context);
                case BPMN_ERROR -> WorkflowTaskOutcome
                    .bpmnError(delivery.bpmnErrorCode(), delivery.bpmnErrorName());
              });
    } catch (final IllegalArgumentException e) {
      log.warn(
          "The recorded delivery '{}' of task '{}' (BPMN process '{}' of workflow module '{}') "
              + "reports the unknown outcome '{}' - processing the delivery again",
          delivery.deliveryKey(),
          context.getTaskDefinition(),
          bpmnProcessId,
          workflowModuleId,
          delivery.outcome());
      return java.util.Optional.empty();
    }

  }

  /**
   * How long a task left open by a <code>&#64;TaskId</code> handler has been waiting,
   * and whether that passed the maximum age configured for it
   * (<code>vanillabp.delivery.max-task-age</code>, resolvable per workflow module,
   * workflow and task).
   * <p>
   * This is the one place in VanillaBP which can answer the question at all. The record
   * was written when the handler ran and it is what answers every redelivery of that
   * task, so the distance between its timestamp and now IS the age of the open task -
   * no clock, no scheduler and no second bookkeeping are involved, and the granularity
   * follows whatever rhythm the BPMS redelivers in.
   * <p>
   * Reporting is one WARN per task, not one per redelivery: a task open for a year would
   * otherwise fill the log with the same line every time its lock is renewed. The memory
   * of what was already reported is bounded and lives in this process service - losing
   * an entry costs one repeated WARN, which is why it needs nothing durable.
   * <p>
   * This is also the one place which knows that a record is still in use, whichever BPMS
   * redelivered: the store is told so and keeps the record alive as long as
   * redeliveries keep coming, while the timestamp this age is measured from stays where
   * it is. The store collects the key rather than writing it here - the redelivery runs
   * in the transaction of the workflow aggregate, and an UPDATE per renewal of every open
   * task has no business in it.
   *
   * @param deliveryLog The store the record came from
   * @param delivery The record answering this delivery
   * @param context The invocation context of the repeated delivery
   * @return The outcome, carrying the age and whether it passed the maximum
   */
  private WorkflowTaskOutcome stillOpen(
      final TaskDeliveryLog deliveryLog,
      final TaskDelivery delivery,
      final TaskInvocationContext context) {

    deliveryLog.stillOpen(delivery.deliveryKey());

    if (delivery.recordedAt() == null) {
      // a store written before the timestamp was part of the record - the task is
      // open, its age is simply not known
      return WorkflowTaskOutcome.completionPending();
    }
    final var openFor = java.time.Duration.between(delivery.recordedAt(), java.time.Instant.now());
    final var maxTaskAge = properties == null
        ? io.vanillabp.integration.adapter.migration.config.DeliveryProperties.DEFAULT_MAX_TASK_AGE
        : properties.maxTaskAge(workflowModuleId, bpmnProcessId, context.getTaskDefinition());
    final var exceeded = !maxTaskAge.isZero() && (openFor.compareTo(maxTaskAge) > 0);
    // a workflow which was already running before this version was deployed was open
    // before VanillaBP ever wrote a record for it, so the record's timestamp is the
    // moment the task was first SEEN and not the moment it was created
    final var lowerBound = context.predatesDeployedVersion();
    if (exceeded && reportTaskAgeOnce(delivery.deliveryKey())) {
      log.warn(
          """
              Task '{}' of BPMN process '{}' of workflow module '{}' has been waiting for its \
              asynchronous completion for {}{}, which is longer than the {} configured by '{}' for it. \
              Workflow aggregate: '{}'. Either the application still owes this task a \
              'ProcessService#completeTask' respectively 'cancelTask', or nobody will ever send it \
              and the workflow waits forever. Raise the maximum age where such a wait is legitimate \
              (it may be set per workflow module, workflow and task), or set it to '0' to switch \
              this report off.{}""",
          context.getTaskDefinition(),
          bpmnProcessId,
          workflowModuleId,
          lowerBound
              ? "at least "
              : "",
          openFor,
          maxTaskAge,
          MigrationAdapterProperties.maxTaskAgeProperty(),
          context.getWorkflowAggregateId(),
          lowerBound
              ? " This workflow was already running before the version deployed now, so it was open"
                  + " before VanillaBP could write anything down about it: the age above counts from"
                  + " the first delivery this application saw, and the real one is larger."
              : "");
    }
    return WorkflowTaskOutcome.completionPending(openFor, exceeded);

  }

  /**
   * How many open tasks this process service remembers having reported. A bound rather
   * than a growing set: the entry is a hint, and forgetting one costs one repeated WARN.
   */
  private static final int REPORTED_TASK_AGES = 1000;

  private final java.util.Map<String, Boolean> reportedTaskAges = java.util.Collections
      .synchronizedMap(new java.util.LinkedHashMap<String, Boolean>(16, 0.75f, true) {

        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(
            final java.util.Map.Entry<String, Boolean> eldest) {

          return size() > REPORTED_TASK_AGES;

        }

      });

  /**
   * Whether the given task's age is reported now - <code>true</code> exactly once per
   * delivery key, which is once per task.
   *
   * @param deliveryKey The identity of the delivery answering this task
   * @return Whether to log the report
   */
  private boolean reportTaskAgeOnce(
      final String deliveryKey) {

    return reportedTaskAges.putIfAbsent(deliveryKey, Boolean.TRUE) == null;

  }

  /**
   * Records which adapter holds the workflow of the given aggregate, for the
   * moments VanillaBP knows it without asking anybody: scheduling a start (the
   * elected adapter is decided then), phase two of that start, and every inbound
   * delivery (a task, a user task, a start the BPMS performed). The end of a
   * workflow is the exception and goes to
   * {@link #rememberWorkflowEnded(Object, String)}.
   * <p>
   * Recording at SCHEDULING time is what makes an operation following the start
   * right away work at all: on a remote BPMS the instance is created after the
   * commit, so an operation in the next transaction would otherwise find no hint
   * and fail instead of waiting. The entry is a hint like every other one - a
   * rolled-back start leaves one behind, at the price of one waited-out window the
   * next time somebody asks for that aggregate ID.
   * <p>
   * The next operation on that workflow probes the recorded adapter first, and an
   * adapter whose BPMS is eventually consistent gets a second look there instead of
   * an immediate failure. Nothing is recorded where the caller knows no adapter id
   * (an adapter written before the inbound contexts carried it).
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param adapterId The ID of the adapter holding the workflow or <code>null</code>
   */
  public void rememberWorkflowAdapter(
      final Object workflowAggregateId,
      final String adapterId) {

    workflowLocator.remember(workflowAggregateId, adapterId);

  }

  /**
   * Records that the workflow of the given aggregate ENDED in the given adapter -
   * called by the notification the BPMS sends when it does. The hint stays readable,
   * because an operation which crossed the end still has to become the warned no-op it
   * was before, but it is not refreshed: the end was the one inbound delivery which
   * extended the lifetime of a hint at the very moment it became useless.
   * <p>
   * How long a marked hint lives is the cache's business - the in-memory default keeps
   * it for <code>vanillabp.workflow-adapter-cache.ended-time-to-live</code>, an
   * application's own cache for as long as it implements
   * {@link io.vanillabp.integration.spi.WorkflowAdapterCache#putEnded} to say so.
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   * @param adapterId The ID of the adapter which held the ended workflow or
   *        <code>null</code>
   */
  public void rememberWorkflowEnded(
      final Object workflowAggregateId,
      final String adapterId) {

    workflowLocator.rememberWorkflowEnded(workflowAggregateId, adapterId);

  }

  /**
   * The startup check cannot see a transactional proxy three calls down the handler's
   * call chain, the transaction's state can. Asked on both paths, the normal one
   * included: a handler swallowing an exception thrown by a nested transactional bean
   * returns normally and would otherwise report the task as completed while nothing
   * was persisted.
   * <p>
   * Throwing costs nothing that is not lost already, since the transaction cannot
   * commit either way. What it buys is that the failure the BPMS reports names the
   * cause instead of leaving the developer with Arjuna's or Spring's wording one layer
   * away from it.
   */
  private void failIfRollbackOnly(
      final WorkflowTaskHandler handler,
      final TaskInvocationContext context,
      final TransactionRunner transactionRunner,
      final List<String> rollbackRuleRemedies) {

    if (!transactionRunner.isRollbackOnly()) {
      return;
    }
    throw new IllegalStateException(
        """
            The transaction of workflow task '%s' (BPMN process '%s' of workflow module '%s') was \
            marked rollback-only while the @WorkflowTask method '%s' was running, so neither the \
            changes to the workflow aggregate nor the state of the BPMS can be committed! A \
            transaction annotation of the application, on the method or on any bean it called, saw \
            an exception and requested the rollback; VanillaBP's TaskException is the usual \
            candidate, since it is a business outcome for VanillaBP but an ordinary \
            RuntimeException for the transaction interceptor. To solve this either remove that \
            annotation from the call path of the workflow task or exclude \
            io.vanillabp.spi.service.TaskException from its rollback rules%s"""
            .formatted(
                context.getTaskDefinition(),
                bpmnProcessId,
                workflowModuleId,
                handler.describe(),
                describeRollbackRuleRemedies(rollbackRuleRemedies)));

  }

  /**
   * How the rollback rules are written on THIS platform, supplied by the platform
   * integration (an annotation Quarkus does not honor is none of the developer's options
   * there). The names of the annotations' attributes are unknown to the core, and the
   * annotation which marked the transaction cannot be identified at all: it sits on some
   * bean of the call chain, not on the handler.
   */
  private static String describeRollbackRuleRemedies(
      final List<String> rollbackRuleRemedies) {

    if ((rollbackRuleRemedies == null) || rollbackRuleRemedies.isEmpty()) {
      return ".";
    }
    return ": "
        + String.join(" or ", rollbackRuleRemedies)
        + ".";

  }

  private PhaseTwoOutbox resolvePhaseTwoOutbox() {

    if ((phaseTwoOutbox == null) && (phaseTwoOutboxResolver != null)) {
      phaseTwoOutbox = phaseTwoOutboxResolver.resolveFor(workflowAggregateClass);
    }
    return phaseTwoOutbox;

  }

  /**
   * The adapter id an operation is about: the elected one, or - where nothing reported
   * the workflow yet - the one the hint names.
   */
  private String adapterIdOf(
      final WorkflowLocator.Location<A> location) {

    return location.adapter() != null
        ? location.adapter().getAdapterId()
        : location.hintedAdapterId();

  }

  /**
   * Says that an operation is planned although no BPMS reports the workflow yet.
   * <p>
   * This is the ordinary case on an eventually consistent BPMS: VanillaBP started the
   * workflow (or was handed a delivery for it) seconds ago, so it exists, and the read
   * model the probe searches is a moment behind. Phase one refuses to wait for that
   * inside the caller's transaction, so the operation is planned and the dispatch asks
   * again - it may take the time (decision 27 in the repository's DECISIONS.md).
   *
   * @param hintedAdapterId The adapter which should hold the workflow
   * @param subject The workflow the operation is about
   * @param operationDescription What is being planned
   */
  private void reportNotVisibleYet(
      final String hintedAdapterId,
      final String subject,
      final String operationDescription) {

    log.info(
        "Adapter '{}' should hold the {} but does not report it yet - {} is planned and dispatched "
            + "once its BPMS caught up",
        hintedAdapterId,
        subject,
        operationDescription);

  }

  /**
   * What phase two throws while the workflow is still not findable: the entry is worth
   * repeating, because the hint says the workflow exists. Nobody waits for it here -
   * the dispatching thread serves every other entry of the same store, and one workflow
   * whose BPMS lags behind must not hold operations of workflows which are perfectly
   * findable. So the entry goes back with the window of the adapter which should hold
   * it, which is the time that adapter itself says its read model may need.
   * <p>
   * What ends this for a workflow which never becomes visible is the ATTEMPT COUNTER,
   * not the due time: every repetition counts an attempt, and
   * <code>vanillabp.outbox.block-after-attempts</code> of them leave the entry blocked,
   * which is where an exporter nobody noticed becomes visible. The due time only
   * decides how often the question is asked in between.
   *
   * @param hintedAdapterId The adapter which should hold the workflow
   * @param subject The workflow the operation is about
   * @param operationDescription What could not be dispatched
   * @return The failure to throw
   */
  private RuntimeException stillNotVisible(
      final String hintedAdapterId,
      final String subject,
      final String operationDescription) {

    final var message = """
        Phase two of %s cannot run yet: adapter '%s' should hold the %s - VanillaBP started it \
        there or was handed a delivery for it - but its BPMS still does not report the \
        workflow. The entry is repeated. A workflow which really is gone ends up as a blocked \
        entry; a read model which stopped catching up (a Camunda 8 exporter, for instance) is \
        what to look at first."""
        .formatted(operationDescription, hintedAdapterId, subject);

    final var window = visibilityWindowOf(hintedAdapterId);
    return window == null
        ? new IllegalStateException(message)
        : new PhaseTwoRetryLater(message, window);

  }

  /**
   * How long the given adapter says its BPMS may need until a workflow it holds becomes
   * searchable, or <code>null</code> where it needs no time at all (an embedded BPMS) or
   * is not configured any more. Both leave the store with its own backoff, which is the
   * right answer for a failure nothing knows a better moment for.
   *
   * @param adapterId The adapter to ask
   * @return The visibility window or <code>null</code>
   */
  private Duration visibilityWindowOf(
      final String adapterId) {

    return adapterProcessServices
        .stream()
        .filter(adapter -> adapter.getAdapterId().equals(adapterId))
        .map(MigratableProcessService::workflowVisibilityDelay)
        .filter(delay -> (delay != null) && delay.isWaiting())
        .map(WorkflowVisibilityDelay::window)
        .findFirst()
        .orElse(null);

  }

  private String buildNoOutboxMessage(
      final String adapterId) {

    return """
        Everything BPMN process '%s' of workflow module '%s' sends to its BPMS is dispatched \
        through a PhaseTwoOutbox after the caller's transaction committed, but none is available \
        for aggregate '%s' (adapter '%s')! To solve this either
        %s
        - define your own bean implementing io.vanillabp.integration.spi.PhaseTwoOutbox \
        (assign it to specific aggregates via a io.vanillabp.integration.spi.PhaseTwoOutboxAware bean)."""
        .formatted(
            bpmnProcessId,
            workflowModuleId,
            workflowAggregateClass.getName(),
            adapterId,
            phaseTwoOutboxResolver == null
                ? "- provide a PhaseTwoOutboxResolver (platform integration), or"
                : phaseTwoOutboxResolver.remediesDescription());

  }

  /**
   * Executes an operation on the workflow of the given aggregate: the aggregate is
   * saved, the BPMS which serves the operation is elected, the operation's phase one
   * runs inside the caller's transaction and its phase two is planned in the outbox.
   * <p>
   * This is the one method every outbound call of an application ends up in. What
   * differs per operation is the operation itself - which BPMS serves it
   * ({@link Election}), what its handler asks and does, how its idempotency key is
   * built - so an operation added later is a constant in {@link PhaseOperation} and a
   * handler per adapter, and nothing here.
   *
   * @param operation The operation to execute
   * @param workflowAggregate The workflow aggregate, <code>null</code> for an operation
   *          which is not about one workflow (a broadcast signal)
   * @param args The operation's arguments, see {@link PhaseTwoCall#args()}
   * @return The attached workflow aggregate, <code>null</code> where none was given
   */
  public A execute(
      final PhaseOperation operation,
      final A workflowAggregate,
      final Map<String, String> args) {

    return switch (operation.election()) {
      case STARTS_THE_WORKFLOW -> startWorkflow(operation, workflowAggregate, args);
      case HOLDS_THE_TASK, HOLDS_THE_USER_TASK, HOLDS_THE_WORKFLOW -> addressWorkflow(
          operation, workflowAggregate, args);
      case EVERY_DEPLOYED_BPMS -> broadcast(operation, args);
      case OWN_DISPATCH -> throw notAProcessServiceOperation(operation);
    };

  }

  /**
   * Starts the workflow of a newly saved aggregate.
   *
   * @param workflowAggregate The workflow aggregate
   * @return The attached workflow aggregate
   */
  public A startWorkflow(
      final A workflowAggregate) {

    return execute(PhaseOperation.START_WORKFLOW, workflowAggregate, Map.of());

  }

  /**
   * Starts a new workflow by a message start event - start semantics like
   * {@link #startWorkflow(Object)}: the FIRST prioritized adapter starts, its ID is
   * persisted with the outbox entry, and a workflow is started at most once per
   * aggregate.
   *
   * @param workflowAggregate The workflow aggregate
   * @param messageName The BPMN message name of the message start event
   * @return The attached workflow aggregate
   */
  public A startWorkflowByMessage(
      final A workflowAggregate,
      final String messageName) {

    return execute(
        PhaseOperation.START_WORKFLOW_BY_MESSAGE,
        workflowAggregate,
        Map.of(PhaseTwoCall.ARG_MESSAGE_NAME, messageName));

  }

  /**
   * Completes an asynchronous task (a <code>&#64;WorkflowTask</code> method with a
   * <code>&#64;TaskId</code> parameter returned without completing).
   *
   * @param workflowAggregate The workflow aggregate
   * @param taskId The task's ID (as reported to the <code>&#64;TaskId</code> parameter)
   * @return The attached workflow aggregate
   */
  public A completeTask(
      final A workflowAggregate,
      final String taskId) {

    return execute(PhaseOperation.COMPLETE_TASK, workflowAggregate, Map.of(PhaseTwoCall.ARG_TASK_ID, taskId));

  }

  /**
   * Cancels an asynchronous task by BPMN error.
   *
   * @param workflowAggregate The workflow aggregate
   * @param taskId The task's ID
   * @param bpmnErrorCode The error code to be caught by BPMN error boundary events
   * @return The attached workflow aggregate
   */
  public A cancelTask(
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    return execute(
        PhaseOperation.CANCEL_TASK,
        workflowAggregate,
        Map.of(PhaseTwoCall.ARG_TASK_ID, taskId, PhaseTwoCall.ARG_BPMN_ERROR_CODE, bpmnErrorCode));

  }

  /**
   * Completes a USER task - user-task IDs live in a namespace of their own, which is
   * why this is an operation of its own.
   *
   * @param workflowAggregate The workflow aggregate
   * @param taskId The user task's ID
   * @return The attached workflow aggregate
   */
  public A completeUserTask(
      final A workflowAggregate,
      final String taskId) {

    return execute(PhaseOperation.COMPLETE_USER_TASK, workflowAggregate, Map.of(PhaseTwoCall.ARG_TASK_ID, taskId));

  }

  /**
   * Cancels a USER task by BPMN error.
   *
   * @param workflowAggregate The workflow aggregate
   * @param taskId The user task's ID
   * @param bpmnErrorCode The error code to be caught by BPMN error boundary events
   * @return The attached workflow aggregate
   */
  public A cancelUserTask(
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

    return execute(
        PhaseOperation.CANCEL_USER_TASK,
        workflowAggregate,
        Map.of(PhaseTwoCall.ARG_TASK_ID, taskId, PhaseTwoCall.ARG_BPMN_ERROR_CODE, bpmnErrorCode));

  }

  /**
   * Correlates a message with the aggregate's workflow. PAYLOAD DOCTRINE: no message
   * content travels to the BPMS - only the message name and the optional correlation
   * id.
   *
   * @param workflowAggregate The workflow aggregate
   * @param messageName The BPMN message name
   * @param correlationId The correlation id or <code>null</code>
   * @return The attached workflow aggregate
   */
  public A correlateMessage(
      final A workflowAggregate,
      final String messageName,
      final String correlationId) {

    final var args = new java.util.LinkedHashMap<String, String>();
    args.put(PhaseTwoCall.ARG_MESSAGE_NAME, messageName);
    if (correlationId != null) {
      args.put(PhaseTwoCall.ARG_CORRELATION_ID, correlationId);
    }
    return execute(PhaseOperation.CORRELATE_MESSAGE, workflowAggregate, args);

  }

  /**
   * Pushes a changed workflow-aggregate to the BPMS holding its workflow.
   * <p>
   * WHICH values travel is the sync model's business ({@code @SyncWithBPMS}), not this
   * method's. WHERE they land depends on the task ID: <code>null</code> means the
   * workflow's global scope, a task ID means the scope of that task instance only - a
   * task-scoped push deliberately leaves the global values as they were.
   *
   * @param workflowAggregate The workflow aggregate
   * @param taskId The ID of the task whose scope receives the values, or
   *        <code>null</code> for the workflow's global scope
   * @return The attached workflow aggregate
   */
  public A aggregateChanged(
      final A workflowAggregate,
      final String taskId) {

    return execute(
        PhaseOperation.AGGREGATE_CHANGED,
        workflowAggregate,
        taskId == null
            ? Map.of()
            : Map.of(PhaseTwoCall.ARG_TASK_ID, taskId));

  }

  /**
   * Broadcasts a BPMN signal to every BPMS the workflow module is deployed to.
   * <p>
   * A signal is not addressed to a workflow, so nothing is probed and no aggregate is
   * loaded or saved. Every deployed BPMS is asked, not only the first-priority one:
   * during a migration the workflows waiting for the signal are spread across them, and
   * a broadcast reaching half of them would be worse than none.
   *
   * @param signalName The PLAIN BPMN signal name
   */
  public void sendSignal(
      final String signalName) {

    if ((signalName == null) || signalName.isBlank()) {
      throw new IllegalArgumentException(
          """
              No signal name given (BPMN process '%s' of workflow module '%s')! Pass the signal name \
              as it is modelled - VanillaBP applies the name scoping of the workflow module."""
              .formatted(bpmnProcessId, workflowModuleId));
    }
    execute(PhaseOperation.SEND_SIGNAL, null, Map.of(PhaseTwoCall.ARG_SIGNAL_NAME, signalName));

  }

  /**
   * Executes phase two of an operation, dispatched by the {@link PhaseTwoRouter} after
   * the local transaction was committed. The BPMS is elected the way the operation says
   * ({@link Election}): the adapter persisted with the entry for the operations which
   * chose one in phase one, a fresh probe for the operations which address whichever
   * BPMS holds the workflow now.
   *
   * @param operation The operation to execute
   * @param workflowAggregateId The ID of the workflow aggregate in its own type, or
   *          <code>null</code> for an operation which is not about one workflow
   * @param adapterId The adapter ID persisted with the entry, or <code>null</code>
   * @param args The operation's arguments as they were scheduled
   * @param previouslyAttempted Whether the outbox entry was dispatched before
   */
  public void executePhaseTwo(
      final PhaseOperation operation,
      final Object workflowAggregateId,
      final String adapterId,
      final Map<String, String> args,
      final boolean previouslyAttempted) {

    switch (operation.election()) {
      case STARTS_THE_WORKFLOW -> startWorkflowPhaseTwo(
          operation, workflowAggregateId, adapterId, args, previouslyAttempted);
      case HOLDS_THE_TASK, HOLDS_THE_USER_TASK, HOLDS_THE_WORKFLOW -> addressWorkflowPhaseTwo(
          operation, workflowAggregateId, args);
      case EVERY_DEPLOYED_BPMS -> broadcastPhaseTwo(operation, adapterId, args);
      case OWN_DISPATCH -> throw notAProcessServiceOperation(operation);
    }

  }

  /**
   * Phase one of an operation which STARTS a workflow: there is nothing to probe yet,
   * so the first prioritized adapter is elected and its id travels with the entry -
   * phase two uses exactly this adapter instead of re-electing one from the then-current
   * priorities.
   */
  private A startWorkflow(
      final PhaseOperation operation,
      final A workflowAggregate,
      final Map<String, String> args) {

    // persist to get ID in case of @Id @GeneratedValue
    // or force optimistic locking exceptions before running
    // the workflow if aggregate was already persisted before
    final var attachedAggregate = aggregatePersistenceSupport
        .save(workflowAggregate);

    final var aggregateId = aggregatePersistenceSupport
        .getAggregateId(attachedAggregate);

    // checked ONCE here for all adapters: the aggregate's ID is the workflow's
    // identifier (business key / process variable) and the outbox idempotency key
    if ((aggregateId == null) || aggregateId.toString().isBlank()) {
      throw new IllegalStateException(
          """
              The ID of the workflow aggregate of class '%s' is null or blank after saving! The ID \
              identifies the workflow in the BPMS (business key / process variable) and is part of \
              the start's idempotency key - assign it before %s or use a generated ID which is \
              assigned on save."""
              .formatted(workflowAggregateClass.getName(), operation.describe(args)));
    }

    final var adapter = adapterProcessServices
        .getFirst();

    handlerOf(adapter, operation, args)
        .phaseOne(phaseOneRequest(attachedAggregate, args));

    schedulePhaseTwo(operation, aggregateId, adapter.getAdapterId(), adapter.getAdapterId(), args);

    // the workflow belongs to this adapter from now on, which the next operation on
    // it has to know BEFORE phase two ran: on a remote BPMS the start is dispatched
    // asynchronously, so an operation following right away would otherwise find no
    // hint at all and fail instead of waiting for the BPMS to catch up
    rememberWorkflowAdapter(aggregateId, adapter.getAdapterId());

    return attachedAggregate;

  }

  /**
   * Phase two of an operation which STARTS a workflow. The adapter elected in phase one
   * was persisted with the outbox entry and is used here - there is no re-election from
   * the then-current priorities. If the entry was dispatched before (a recovered or
   * retried entry), the re-dispatch mitigation probes
   * {@link MigratableProcessService#awarenessOfWorkflowForRedispatch} on the recorded
   * adapter FIRST: a workflow already known there means the previous dispatch already
   * started it - the entry is consumed without starting a second instance. The residual
   * at-least-once window (a crash between the remote start and marking the entry done,
   * before any awareness lag caught up) remains and is ACCEPTED - this mitigation
   * minimizes duplicates, it does not close the window.
   */
  private void startWorkflowPhaseTwo(
      final PhaseOperation operation,
      final Object workflowAggregateId,
      final String adapterId,
      final Map<String, String> args,
      final boolean previouslyAttempted) {

    final var subject = subjectOf(workflowAggregateId);
    final var adapter = adapterProcessServices
        .stream()
        .filter(processService -> processService.getAdapterId().equals(adapterId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            """
                Cannot execute phase two of %s of %s: adapter '%s' is not (or no longer) configured! \
                The outbox entry is stale - the adapter was probably removed from the configuration \
                (property 'vanillabp.prioritized-adapters' or its module-/workflow-level overrides) \
                after the entry was scheduled. Restore the adapter's configuration or remove the \
                entry from the outbox store."""
                .formatted(operation.describe(args), subject, adapterId)));

    if (previouslyAttempted && skipRedispatchedStart(adapter, workflowAggregateId, operation.describe(args))) {
      return;
    }
    runPhaseTwo(
        adapter,
        "%s of %s".formatted(operation.describe(args), subject),
        () -> handlerOf(adapter, operation, args).phaseTwo(phaseTwoRequest(workflowAggregateId, args)));
    // the workflow exists now, and this adapter created it: the next operation on it
    // (the classic one is correlating the message which lets it continue) probes this
    // adapter first and waits out its BPMS' visibility delay instead of failing
    rememberWorkflowAdapter(workflowAggregateId, adapterId);

  }

  /**
   * The re-dispatch mitigation: probes whether the recorded adapter already knows
   * the workflow of a previously attempted START entry.
   *
   * @return Whether the start has to be SKIPPED (the workflow already exists -
   *         the previous dispatch succeeded)
   * @throws IllegalStateException If the BPMS is unavailable - the outbox entry
   *         stays pending and is retried
   */
  private boolean skipRedispatchedStart(
      final MigratableProcessService<A> adapter,
      final Object workflowAggregateId,
      final String operationDescription) {

    final var awareness = adapter.awarenessOfWorkflowForRedispatch(workflowScope(), aggregatePersistenceSupport,
        workflowAggregateId);
    switch (awareness) {
      case ACTIVE, COMPLETED -> {
        log.info(
            "Skipped re-dispatched phase two of {} of aggregate '{}' (BPMN process '{}' of "
                + "workflow module '{}'): adapter '{}' already knows the workflow ({}) - the "
                + "previous dispatch attempt succeeded, the outbox entry is consumed without "
                + "starting a second instance",
            operationDescription,
            workflowAggregateId,
            bpmnProcessId,
            workflowModuleId,
            adapter.getAdapterId(),
            awareness);
        return true;
      }
      case BPMS_UNAVAILABLE -> throw new IllegalStateException(
          """
              The BPMS of adapter '%s' is unavailable while probing whether the workflow of \
              aggregate '%s' (BPMN process '%s' of workflow module '%s') was already started by a \
              previous dispatch attempt! The outbox entry stays pending and is retried."""
              .formatted(adapter.getAdapterId(), workflowAggregateId, bpmnProcessId, workflowModuleId));
      default -> {
        // UNKNOWN_TO_BPMS - the previous attempt did not start the workflow (or
        // the adapter cannot tell): proceed, the adapter's phase two is idempotent
      }
    }
    return false;

  }

  /**
   * Phase one of an operation addressed to whichever BPMS holds the task respectively
   * the workflow: the aggregate is saved, the adapters are probed the way the
   * operation's election says, the elected adapter's handler asks its question and the
   * operation is planned in the outbox.
   */
  private A addressWorkflow(
      final PhaseOperation operation,
      final A workflowAggregate,
      final Map<String, String> args) {

    // persist changes made before the operation - identical to a workflow start, the
    // aggregate rides the caller's transaction
    final var attachedAggregate = aggregatePersistenceSupport
        .save(workflowAggregate);
    final var aggregateId = aggregatePersistenceSupport
        .getAggregateId(attachedAggregate);

    final var subject = subjectOf(aggregateId);

    final var recordedLocation = locateFromDeliveryRecord(operation, aggregateId, args);
    final var location = recordedLocation != null
        ? recordedLocation
        : workflowLocator.locate(
            adapterProcessServices,
            adapter -> probe(operation, adapter, aggregateId, args),
            aggregateId,
            subject,
            WorkflowLocator.Patience.NONE);

    switch (location.awareness()) {
      case COMPLETED -> {
        // what the operation would advance is over: a no-op with a warning, and the
        // aggregate is saved either way - which is what the caller mainly wanted
        log.warn(
            "Ignored {} of {}: adapter '{}' reports it as already completed",
            operation.describe(args),
            subject,
            location.adapter().getAdapterId());
        return attachedAggregate;
      }
      case UNKNOWN_TO_BPMS -> {
        if (!addressesTheWorkflow(operation) || !location.isUnknownButExpected()) {
          throw unknownToEveryBpms(operation, args, subject);
        }
        // the workflow exists - the hinted adapter holds it - but its read model has
        // not caught up. Plan the operation and let the dispatch ask again, where
        // waiting costs an attempt instead of the caller's database connection
        reportNotVisibleYet(location.hintedAdapterId(), subject, operation.describe(args));
      }
      default -> {
        // ACTIVE - fall through
      }
    }

    final var adapter = location.adapter();
    if (adapter != null) {
      handlerOf(adapter, operation, args)
          .phaseOne(phaseOneRequest(attachedAggregate, args));
    }

    // NO adapter id is persisted: the BPMS which holds the workflow is asked again at
    // dispatch time, because between the two phases a migration may have moved it
    schedulePhaseTwo(operation, aggregateId, null, adapterIdOf(location), args);

    return attachedAggregate;

  }

  /**
   * Phase two of an operation addressed to whichever BPMS holds the task respectively
   * the workflow - the election runs again here, and what is gone by now is a stale
   * entry (logged, consumed).
   */
  private void addressWorkflowPhaseTwo(
      final PhaseOperation operation,
      final Object workflowAggregateId,
      final Map<String, String> args) {

    final var subject = subjectOf(workflowAggregateId);

    // an unreachable BPMS is worth a second question here, where no application
    // transaction is open. A read model which has not caught up is not waited for: this
    // thread dispatches the entries of every workflow of this store, and the entry of
    // the one workflow nobody can find yet is given back with a due time instead
    final var location = workflowLocator.locate(
        adapterProcessServices,
        adapter -> probe(operation, adapter, workflowAggregateId, args),
        workflowAggregateId,
        subject,
        WorkflowLocator.Patience.RETRY_UNAVAILABLE);

    switch (location.awareness()) {
      case UNKNOWN_TO_BPMS -> {
        if (addressesTheWorkflow(operation) && location.isUnknownButExpected()) {
          throw stillNotVisible(location.hintedAdapterId(), subject, operation.describe(args));
        }
        log.warn(
            "Skipped phase two of {} of {}: no configured BPMS knows it any more (a stale outbox "
                + "entry - it disappeared between scheduling and dispatch); the entry is consumed",
            operation.describe(args),
            subject);
      }
      case COMPLETED -> log.warn(
          "Skipped phase two of {} of {}: it has ended already; the entry is consumed",
          operation.describe(args),
          subject);
      // BPMS_UNAVAILABLE cannot reach here (locate throws) - the outbox retries
      default -> {
        runPhaseTwo(
            location.adapter(),
            "%s of %s".formatted(operation.describe(args), subject),
            () -> handlerOf(location.adapter(), operation, args)
                .phaseTwo(phaseTwoRequest(workflowAggregateId, args)));
        writeDownThatTheTaskIsClosed(operation, workflowAggregateId, args);
      }
    }

  }

  /**
   * Answers from the delivery record of this aggregate which adapter holds the task THIS
   * CALL names, so no BPMS has to be asked for it.
   * <p>
   * VanillaBP wrote that record while the handler of the task ran, in the database of the
   * workflow aggregate: it names the adapter which delivered the task, and it says whether
   * the application has closed that task since. Both answers are exactly what the walk over
   * the adapters would ask a BPMS for, one round trip per operation - on Camunda 8 the very
   * command the adapter's phase one sends again a moment later.
   * <p>
   * What decides whether the record can answer is the CALL rather than the operation: a call
   * which names a task asks about that task, however the operation is elected. Pushing a
   * changed aggregate into the scope of a task is the case where the two differ - the values
   * land in the workflow, and the task the call names is what says where. As long as that task
   * is open, the BPMS holding it is the BPMS holding the workflow around it, which is why the
   * answer stays one about a task (see decision 30 in the repository's DECISIONS.md). A task
   * which is over says nothing about the workflow around it, so a closed record answers only
   * the operations which end the task themselves.
   * <p>
   * <code>null</code> means the record cannot answer, and then everything happens as it did
   * before: no store, deliveries not deduplicated, the retention gone over the record, a BPMS
   * which reports no delivery identity, a workflow started before this version was deployed,
   * or an adapter which is not configured any more. That fallback is what keeps the record a
   * hint rather than a registry.
   *
   * @param operation The operation being elected for
   * @param workflowAggregateId The workflow aggregate the operation is about
   * @param args The operation's arguments, which name the task
   * @return The location, or <code>null</code> where the record cannot answer
   */
  private WorkflowLocator.Location<A> locateFromDeliveryRecord(
      final PhaseOperation operation,
      final Object workflowAggregateId,
      final Map<String, String> args) {

    if (workflowAggregateId == null) {
      return null;
    }
    final var taskId = theTaskNamedBy(args);
    if (taskId == null) {
      return null;
    }
    final var deliveryLog = resolveTaskDeliveryLog();
    if (deliveryLog == null) {
      return null;
    }
    final var record = deliveryLog
        .recordOfTask(workflowModuleId, bpmnProcessId, workflowAggregateId.toString(), taskId)
        .orElse(null);
    if ((record == null) || (record.adapterId() == null)) {
      return null;
    }
    final var adapter = adapterProcessServices
        .stream()
        .filter(candidate -> candidate.getAdapterId().equals(record.adapterId()))
        .findFirst()
        .orElse(null);
    if (adapter == null) {
      // the adapter which delivered the task is not prioritized for this workflow any more -
      // the walk elects from the configuration as it reads now
      log.debug(
          "Adapter '{}' recorded for task '{}' of aggregate '{}' is not a prioritized adapter - "
              + "probing instead",
          record.adapterId(),
          taskId,
          workflowAggregateId);
      return null;
    }
    final var taskIsOpen = record.taskClosedAt() == null;
    if (!taskIsOpen && !endsTheTaskItNames(operation)) {
      // a task which is over is no statement about the workflow around it: the scope the
      // push writes into outlives the task, and so may the workflow. Only an operation
      // which ends the task itself may read a closed record as "there is nothing left to
      // do here"
      log.debug(
          "Task '{}' of aggregate '{}' was closed at {}, which does not say whether its workflow "
              + "still runs - probing instead",
          taskId,
          workflowAggregateId,
          record.taskClosedAt());
      return null;
    }
    metrics
        .taskElectionAnsweredFromRecord(record.adapterId(), workflowModuleId, bpmnProcessId, operation.name());
    log.debug(
        "Adapter '{}' delivered task '{}' of aggregate '{}' and the task is {} - no BPMS is asked "
            + "which of them holds it",
        record.adapterId(),
        taskId,
        workflowAggregateId,
        taskIsOpen
            ? "still open"
            : "closed since %s".formatted(record.taskClosedAt()));
    return new WorkflowLocator.Location<>(
        taskIsOpen
            ? WorkflowAwareness.ACTIVE
            : WorkflowAwareness.COMPLETED, adapter, null);

  }

  /**
   * Writes into the delivery record that the application's completion or cancellation of a
   * task reached the BPMS - here and not when the caller asked, because until this moment the
   * task is still open and its redeliveries still renew the lock the BPMS holds on it.
   * <p>
   * A failure is reported and swallowed: the operation itself went through, the outbox entry
   * is done, and repeating a dispatch which succeeded because a mark did not is the worse of
   * the two. What is lost is one BPMS round trip on the next operation naming that task.
   * <p>
   * Asked of the OPERATION and not of the call, unlike the election in
   * {@link #locateFromDeliveryRecord(PhaseOperation, Object, Map)}: a call which merely names
   * a task leaves that task open - pushing a changed aggregate into its scope completes
   * nothing - so writing the moment of a completion there would close a record while the BPMS
   * still hands the task out.
   *
   * @param operation The operation which was dispatched
   * @param workflowAggregateId The workflow aggregate it was about
   * @param args The operation's arguments, which name the task
   */
  private void writeDownThatTheTaskIsClosed(
      final PhaseOperation operation,
      final Object workflowAggregateId,
      final Map<String, String> args) {

    if (!endsTheTaskItNames(operation) || (workflowAggregateId == null)) {
      return;
    }
    final var taskId = theTaskNamedBy(args);
    if (taskId == null) {
      return;
    }
    final var deliveryLog = resolveTaskDeliveryLog();
    if (deliveryLog == null) {
      return;
    }
    try {
      deliveryLog
          .markTaskClosed(workflowModuleId, bpmnProcessId, workflowAggregateId.toString(), taskId);
    } catch (final RuntimeException e) {
      log.warn(
          "Task '{}' of {} was closed in its BPMS, but the delivery record could not be marked "
              + "accordingly - the next operation naming this task asks the configured BPMS again "
              + "instead of being answered from the record",
          taskId,
          subjectOf(workflowAggregateId),
          e);
    }

  }

  /**
   * Whether the operation ENDS the task it names, which is what an operation elected by
   * whoever holds a task does: completing it or cancelling it. An operation elected by
   * whoever holds the WORKFLOW may name a task as well, and then it says where its values
   * go rather than that the task is finished.
   */
  private static boolean endsTheTaskItNames(
      final PhaseOperation operation) {

    return (operation.election() == Election.HOLDS_THE_TASK) || (operation.election() == Election.HOLDS_THE_USER_TASK);

  }

  /**
   * The task a call names, or <code>null</code> where it names none - the question which
   * decides whether the delivery record of a task can be asked at all.
   */
  private static String theTaskNamedBy(
      final Map<String, String> args) {

    final var taskId = args.get(PhaseTwoCall.ARG_TASK_ID);
    return ((taskId == null) || taskId.isBlank())
        ? null
        : taskId;

  }

  /**
   * Phase one of an operation every deployed BPMS gets: one outbox entry per BPMS, each
   * carrying the adapter it was written for.
   */
  private A broadcast(
      final PhaseOperation operation,
      final Map<String, String> args) {

    final var targets = deploymentAdapterProcessServices.isEmpty()
        ? adapterProcessServices
        : deploymentAdapterProcessServices;

    RuntimeException failure = null;
    for (final var adapter : targets) {
      try {
        handlerOf(adapter, operation, args)
            .phaseOne(phaseOneRequest(null, args));
        schedulePhaseTwo(operation, null, adapter.getAdapterId(), adapter.getAdapterId(), args);
      } catch (final RuntimeException e) {
        // every BPMS is asked before the first failure is reported: a broadcast
        // which stopped at the first unreachable BPMS would leave the others
        // waiting, and the outbox retries what was scheduled anyway
        log.error(
            "{} of workflow module '{}' through adapter '{}' failed",
            operation.describe(args),
            workflowModuleId,
            adapter.getAdapterId(),
            e);
        if (failure == null) {
          failure = e;
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
    return null;

  }

  /**
   * Phase two of an operation every deployed BPMS gets. The adapter of the entry is the
   * one it was written for - there is no election, a broadcast is not about a workflow.
   */
  private void broadcastPhaseTwo(
      final PhaseOperation operation,
      final String adapterId,
      final Map<String, String> args) {

    final var adapter = deploymentAdapterProcessServices
        .stream()
        .filter(candidate -> candidate.getAdapterId().equals(adapterId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            """
                Cannot execute phase two of %s of BPMN process '%s' (workflow module '%s'): the \
                adapter '%s' the outbox entry was written for is not configured (any more)! Either \
                restore the adapter's configuration or remove the entry from the outbox store."""
                .formatted(operation.describe(args), bpmnProcessId, workflowModuleId, adapterId)));

    runPhaseTwo(
        adapter,
        "%s of %s".formatted(operation.describe(args), subjectOf(null)),
        () -> handlerOf(adapter, operation, args).phaseTwo(phaseTwoRequest(null, args)));

  }

  /**
   * Plans phase two of an operation in the outbox of this aggregate.
   *
   * @param operation The operation being planned
   * @param workflowAggregateId The aggregate the operation is about, or
   *          <code>null</code> where it is about none
   * @param persistedAdapterId The adapter which has to execute phase two, or
   *          <code>null</code> where it is elected at dispatch time
   * @param electedAdapterId The adapter phase one talked to - names the adapter in the
   *          message about a missing outbox, where the persisted one is null
   * @param args The operation's arguments
   */
  private void schedulePhaseTwo(
      final PhaseOperation operation,
      final Object workflowAggregateId,
      final String persistedAdapterId,
      final String electedAdapterId,
      final Map<String, String> args) {

    final var scheduledArgs = withActivation(operation, args);

    // backstop only: the outbox was already resolved and validated at startup
    // (validatePhaseTwoOutboxAtStartup) - this fires only if that was skipped
    final var outbox = resolvePhaseTwoOutbox();
    if (outbox == null) {
      throw new IllegalStateException(
          buildNoOutboxMessage(electedAdapterId));
    }

    final var scheduled = outbox
        .schedule(
            PhaseTwoCall
                .of(
                    operation,
                    workflowModuleId,
                    bpmnProcessId,
                    workflowAggregateId == null
                        ? null
                        : workflowAggregateId.toString(),
                    persistedAdapterId,
                    scheduledArgs));
    if (!scheduled) {
      reportDiscardedSchedule(
          operation,
          "%s of %s".formatted(operation.describe(scheduledArgs), subjectOf(workflowAggregateId)));
    }

  }

  /**
   * Adds the activation the call is planned in to the arguments of an operation which
   * asked to carry it.
   * <p>
   * Read HERE and not while the idempotency key is derived: this is the one path such
   * an operation is planned on, it runs on the thread the handler runs on, and the
   * value has to reach the adapter at dispatch time as well - by then nothing remembers
   * that thread.
   */
  private static Map<String, String> withActivation(
      final PhaseOperation operation,
      final Map<String, String> args) {

    if (!operation.carriesActivation()) {
      return args;
    }
    final var activationId = io.vanillabp.integration.spi.RunningActivation.current();
    if (activationId == null) {
      return args;
    }
    final var withActivation = new java.util.LinkedHashMap<>(args);
    withActivation.put(PhaseTwoCall.ARG_ACTIVATION_ID, activationId);
    return withActivation;

  }

  /**
   * Asks one adapter the question the operation's election is about.
   */
  private WorkflowAwareness probe(
      final PhaseOperation operation,
      final MigratableProcessService<A> adapter,
      final Object workflowAggregateId,
      final Map<String, String> args) {

    return switch (operation.election()) {
      case HOLDS_THE_TASK -> adapter
          .awarenessOfTask(workflowScope(), workflowAggregateId, args.get(PhaseTwoCall.ARG_TASK_ID));
      case HOLDS_THE_USER_TASK -> adapter
          .awarenessOfUserTask(workflowScope(), workflowAggregateId, args.get(PhaseTwoCall.ARG_TASK_ID));
      case HOLDS_THE_WORKFLOW -> adapter
          .awarenessOfWorkflow(workflowScope(), aggregatePersistenceSupport, workflowAggregateId);
      default -> throw new IllegalStateException(
          "The election '%s' of operation '%s' asks no adapter anything!"
              .formatted(operation.election(), operation.name()));
    };

  }

  /**
   * Whether the operation addresses the workflow itself rather than a task of it, which
   * decides three things: how patiently the BPMS is looked for, whether an operation may
   * be planned although no BPMS reports the workflow yet, and what a failure is called.
   * A workflow is SEARCHED for, so a read model which is a moment behind is the ordinary
   * case on an eventually consistent BPMS; a task is asked about by its ID, which is an
   * exact question, so an unknown task is a definite answer.
   */
  private static boolean addressesTheWorkflow(
      final PhaseOperation operation) {

    return operation.election() == Election.HOLDS_THE_WORKFLOW;

  }

  /**
   * What phase one throws where every configured BPMS says it does not know what the
   * operation addresses.
   */
  private RuntimeException unknownToEveryBpms(
      final PhaseOperation operation,
      final Map<String, String> args,
      final String subject) {

    final var hint = operation.wording().hintWhenUnknown();
    final var message = """
        No configured BPMS can serve %s of %s (probed adapters, in prioritized order: %s)! The \
        aggregate itself was saved. Likely causes: %s%s"""
        .formatted(
            operation.describe(args),
            subject,
            prioritizedAdapters,
            addressesTheWorkflow(operation)
                ? likelyCausesOfUnknownWorkflow()
                : likelyCausesOfUnknownTask(),
            hint.isEmpty()
                ? ""
                : " "
                    + hint);
    return addressesTheWorkflow(operation)
        ? new io.vanillabp.spi.process.WorkflowNotFoundException(message)
        : new io.vanillabp.spi.process.TaskNotFoundException(message);

  }

  private static String likelyCausesOfUnknownTask() {

    return "the task ID is wrong or outdated, the task was already completed long ago, or the "
        + "workflow was terminated. If a BPMS was reported unavailable, this operation would have "
        + "failed differently - an unknown task is a definite answer of all adapters.";

  }

  /**
   * What every message about an operation names: the workflow it happened to. What
   * happened is the operation's own words ({@link PhaseOperation#describe(Map)}).
   */
  private String subjectOf(
      final Object workflowAggregateId) {

    return workflowAggregateId == null
        ? "BPMN process '%s' of workflow module '%s'".formatted(bpmnProcessId, workflowModuleId)
        : "aggregate '%s' (BPMN process '%s' of workflow module '%s')"
            .formatted(workflowAggregateId, bpmnProcessId, workflowModuleId);

  }

  private PhaseOneRequest<A> phaseOneRequest(
      final A workflowAggregate,
      final Map<String, String> args) {

    return new PhaseOneRequest<>(
        workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, workflowAggregate, args);

  }

  private PhaseTwoRequest<A> phaseTwoRequest(
      final Object workflowAggregateId,
      final Map<String, String> args) {

    return new PhaseTwoRequest<>(
        workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, workflowAggregateId, args);

  }

  private static IllegalArgumentException notAProcessServiceOperation(
      final PhaseOperation operation) {

    return new IllegalArgumentException(
        """
            The operation '%s' elects no BPMS (%s): it is dispatched by the extension which \
            contributed it, not through a process service. Register it with a \
            PhaseOperationDispatch of your own instead of routing it here."""
            .formatted(operation.name(), operation.election()));

  }

  /**
   * What ONE adapter does for ONE operation. Answered from the map the adapter
   * contributed, which is read once per adapter: a handler is a collaborator of the
   * adapter, not a per-call object.
   */
  private PhaseOperationHandler<A> handlerOf(
      final MigratableProcessService<A> adapter,
      final PhaseOperation operation,
      final Map<String, String> args) {

    final var handler = phaseOperationsOf(adapter)
        .get(operation);
    if (handler == null) {
      throw new io.vanillabp.integration.adapter.spi.PhaseOperationNotSupported(
          adapter.getAdapterId(), operation, workflowModuleId, bpmnProcessId, args);
    }
    return handler;

  }

  /**
   * The handlers of one adapter, by operation. Cached per adapter id: the adapters of a
   * process service are fixed, and an adapter is free to build its map fresh.
   */
  private Map<PhaseOperation, PhaseOperationHandler<A>> phaseOperationsOf(
      final MigratableProcessService<A> adapter) {

    return phaseOperations
        .computeIfAbsent(adapter.getAdapterId(), adapterId -> adapter.phaseOperations());

  }

  /**
   * The handlers of every adapter of this process service, by adapter id.
   */
  private final Map<String, Map<PhaseOperation, PhaseOperationHandler<A>>> phaseOperations = new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Refuses AT STARTUP an adapter which cannot serve an operation every adapter has to
   * serve.
   * <p>
   * An adapter contributes a handler per operation, and the map may miss one for two
   * reasons: the BPMS has nothing like the operation, which is legitimate for an
   * operation which says so ({@link PhaseOperation#requiredOfEveryAdapter()}), or the
   * adapter was written against an older SPI and never learned about it. The second one
   * would show as a workflow standing still, hours after the application booted, which
   * is why the boot asks instead.
   * <p>
   * The map is the whole question. Asking the adapter's class whether it implements
   * something would mean reflection, and reflection is a lie in a native image: a method
   * nobody registered looks like a method nobody wrote, so every adapter of a native
   * application would be refused.
   */
  public void validateAdapterOperationsAtStartup() {

    java.util.stream.Stream
        .concat(adapterProcessServices.stream(), deploymentAdapterProcessServices.stream())
        .distinct()
        .forEach(this::validateOperationsOf);

  }

  private void validateOperationsOf(
      final MigratableProcessService<A> adapter) {

    final var missing = PhaseOperation.CORE_OPERATIONS
        .stream()
        .filter(PhaseOperation::requiredOfEveryAdapter)
        .filter(operation -> !phaseOperationsOf(adapter).containsKey(operation))
        .map(PhaseOperation::name)
        .toList();
    if (missing.isEmpty()) {
      return;
    }
    throw new IllegalStateException(
        """
            The VanillaBP adapter '%s' cannot serve the operations %s of BPMN process '%s' of \
            workflow module '%s', although every adapter has to: its '%s' contributes no handler \
            for them. Add a PhaseOperationHandler per missing operation - see the javadoc of \
            io.vanillabp.integration.adapter.spi.PhaseOperationHandler - or remove the adapter \
            from the prioritized adapters of this workflow module."""
            .formatted(
                adapter.getAdapterId(),
                missing,
                bpmnProcessId,
                workflowModuleId,
                adapter.getClass().getSimpleName()));

  }

  /**
   * The BPMN processes this process service serves, the primary one first.
   * <p>
   * A {@code @WorkflowService} declares one primary process and may declare
   * {@code secondaryBpmnProcesses}; all of them run on this aggregate, so an instance of
   * any of them is a legitimate answer to an awareness probe. The platform integration
   * knows the full list when it registers the process service and sets it here. Where it
   * is not set - a test constructing this service directly - the primary process is the
   * scope, which is the narrowest honest answer.
   */
  private java.util.List<String> servedBpmnProcessIds;

  /**
   * @param servedBpmnProcessIds The plain BPMN process ids of this aggregate's workflow
   *          services, the primary one first
   */
  public void setServedBpmnProcessIds(
      final java.util.List<String> servedBpmnProcessIds) {

    this.servedBpmnProcessIds = (servedBpmnProcessIds == null) || servedBpmnProcessIds.isEmpty()
        ? null
        : List.copyOf(servedBpmnProcessIds);

  }

  /**
   * What an awareness probe is asked about: this workflow module and the processes this
   * service serves. Built per call rather than cached, because the platform sets the
   * process ids after construction.
   *
   * @return The scope handed to every probe
   */
  private io.vanillabp.integration.adapter.spi.WorkflowScope workflowScope() {

    return servedBpmnProcessIds == null
        ? io.vanillabp.integration.adapter.spi.WorkflowScope.of(workflowModuleId, bpmnProcessId)
        : new io.vanillabp.integration.adapter.spi.WorkflowScope(workflowModuleId, servedBpmnProcessIds);

  }

  /**
   * The viewer/history API: returns the process definitions used by the workflow
   * of the given aggregate. Read-only - the aggregate is NOT saved (unlike every
   * operation advancing a workflow); the BPMS holding the workflow is elected by
   * probing {@code awarenessOfWorkflow} like message correlation does, but a
   * COMPLETED workflow is a perfectly valid subject here (viewers show ended
   * workflows).
   * <p>
   * The adapter-native definition ids are namespaced with the answering adapter's
   * id (see {@link ProcessDefinitionIds}) so {@link #getBpmnXml(String)} - which
   * has no aggregate to elect by - stays resolvable.
   *
   * @param workflowAggregate The workflow aggregate
   * @param historyContext <code>null</code> for the primary process or a
   *        secondary history context of a call activity
   * @return The process definitions
   */
  public List<io.vanillabp.spi.process.ProcessDefinition> getProcessDefinitions(
      final A workflowAggregate,
      final String historyContext) {

    final var aggregateId = aggregatePersistenceSupport
        .getAggregateId(workflowAggregate);
    final var location = locateForReading(aggregateId, "process definitions");
    final var adapter = location.adapter();

    final var definitions = adapter.getProcessDefinitions(
        workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, aggregateId, historyContext);
    if ((definitions == null) || definitions.isEmpty()) {
      throw new io.vanillabp.spi.process.WorkflowNotFoundException(
          workflowUnknownMessage(aggregateId, adapter.getAdapterId(), "process definitions", historyContext));
    }

    return definitions
        .stream()
        .map(definition -> new io.vanillabp.spi.process.ProcessDefinition(
            ProcessDefinitionIds.compose(adapter.getAdapterId(), definition.id()), definition
                .bpmnProcessId(), definition.version(), definition.usedByElements()))
        .toList();

  }

  /**
   * The viewer/history API: returns the BPMN XML of a process definition
   * previously reported by
   * {@link #getProcessDefinitions(Object, String)}. The composite definition id
   * names the adapter which can resolve it - there is no aggregate to elect by.
   *
   * @param processDefinitionId The composite process definition id
   * @return The BPMN XML
   */
  public java.io.InputStream getBpmnXml(
      final String processDefinitionId) {

    final var parsed = ProcessDefinitionIds.parse(processDefinitionId);
    if (parsed == null) {
      throw new io.vanillabp.spi.process.ProcessDefinitionNotFoundException(
          ("The process definition id '%s' does not follow VanillaBP's scheme "
              + "'<adapter id>%s<BPMS specific id>'! Pass an id reported by getProcessDefinitions "
              + "(or WorkflowHistory#processDefinitionId) of BPMN process '%s' of workflow module "
              + "'%s' unchanged - it is opaque to the application.")
              .formatted(
                  processDefinitionId,
                  ProcessDefinitionIds.SEPARATOR,
                  bpmnProcessId,
                  workflowModuleId));
    }

    final var adapter = adapterProcessServices
        .stream()
        .filter(processService -> processService.getAdapterId().equals(parsed.adapterId()))
        .findFirst()
        .orElseThrow(() -> new io.vanillabp.spi.process.ProcessDefinitionNotFoundException(
            ("The process definition id '%s' addresses the adapter '%s' which is not (or no longer) "
                + "configured for BPMN process '%s' of workflow module '%s' (configured adapters, "
                + "in prioritized order: %s)! Either the id was kept from an earlier configuration "
                + "or it belongs to another workflow.")
                .formatted(
                    processDefinitionId,
                    parsed.adapterId(),
                    bpmnProcessId,
                    workflowModuleId,
                    prioritizedAdapters)));

    final var bpmnXml = adapter.getBpmnXml(
        workflowModuleId, bpmnProcessId, parsed.nativeProcessDefinitionId());
    if (bpmnXml == null) {
      throw new io.vanillabp.spi.process.ProcessDefinitionNotFoundException(
          ("The adapter '%s' does not know the process definition '%s' (of BPMN process '%s' of "
              + "workflow module '%s')! Likely causes: the definition was deleted in the BPMS, or "
              + "the id was kept from a previous deployment the BPMS no longer holds.")
              .formatted(
                  parsed.adapterId(),
                  parsed.nativeProcessDefinitionId(),
                  bpmnProcessId,
                  workflowModuleId));
    }
    return bpmnXml;

  }

  /**
   * The viewer/history API: returns the execution history of the workflow of the
   * given aggregate - same election and read-only semantics as
   * {@link #getProcessDefinitions(Object, String)}.
   *
   * @param workflowAggregate The workflow aggregate
   * @param historyContext <code>null</code> for the primary process or a
   *        secondary history context of a call activity
   * @return The workflow history
   */
  public io.vanillabp.spi.process.WorkflowHistory getWorkflowHistory(
      final A workflowAggregate,
      final String historyContext) {

    final var aggregateId = aggregatePersistenceSupport
        .getAggregateId(workflowAggregate);
    final var location = locateForReading(aggregateId, "the workflow history");
    final var adapter = location.adapter();

    final var history = adapter.getWorkflowHistory(
        workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, aggregateId, historyContext);
    if (history == null) {
      throw new io.vanillabp.spi.process.WorkflowNotFoundException(
          workflowUnknownMessage(aggregateId, adapter.getAdapterId(), "the workflow history", historyContext));
    }

    return new io.vanillabp.spi.process.WorkflowHistory(
        ProcessDefinitionIds.compose(adapter.getAdapterId(), history.processDefinitionId()), history
            .startTime(), history.endTime(), history.elementsHistory());

  }

  /**
   * Elects the adapter answering a READ operation of the viewer/history API.
   * Unlike operations advancing a workflow, {@link WorkflowAwareness#COMPLETED} is
   * a regular result here (an ended workflow still has definitions and a history);
   * only a subject unknown to EVERY adapter raises the SPI's
   * {@code WorkflowNotFoundException}.
   * <p>
   * A read is the caller which has to do its own waiting. An operation advancing a
   * workflow is planned in phase one and waits for an eventually consistent read model
   * in the dispatch, where no transaction is open (decision 27 in the repository's
   * DECISIONS.md); a read has no second place to wait in - it answers the caller or it
   * fails, and a failure is not repeated by anybody. So where a hint says which adapter
   * holds the workflow, the read waits out that adapter's
   * {@code workflowVisibilityDelay}: asking for the history of a workflow the same
   * application started seconds ago is the ordinary case, and the one to three seconds
   * Camunda 8's exporter lags behind must not turn it into an error. Without a hint
   * nothing is waited for - a workflow nobody has ever seen fails at once.
   */
  private WorkflowLocator.Location<A> locateForReading(
      final Object aggregateId,
      final String subjectOfRead) {

    final var subject = "workflow of aggregate '%s' (BPMN process '%s' of workflow module '%s')"
        .formatted(aggregateId, bpmnProcessId, workflowModuleId);

    // the hint is what buys the waiting: it says the workflow exists, so an adapter not
    // reporting it yet is asked again until its visibility window is used up. Nothing
    // repeats a read later, so this is the only place it can happen
    final var location = workflowLocator.locate(
        adapterProcessServices,
        adapter -> adapter.awarenessOfWorkflow(workflowScope(), aggregatePersistenceSupport, aggregateId),
        aggregateId,
        subject,
        WorkflowLocator.Patience.WAIT_FOR_VISIBILITY);

    if (location.awareness() == WorkflowAwareness.UNKNOWN_TO_BPMS) {
      throw new io.vanillabp.spi.process.WorkflowNotFoundException(
          ("No configured BPMS knows the %s - %s cannot be determined (probed adapters, in "
              + "prioritized order: %s)! Likely causes: the workflow was never started, was "
              + "started through another system, or its history was already cleaned up in the "
              + "BPMS.%s")
              .formatted(
                  subject,
                  subjectOfRead,
                  prioritizedAdapters,
                  location.isUnknownButExpected()
                      ? (" The adapter '%s' was expected to hold this workflow (VanillaBP started "
                          + "it there or was handed a delivery for it) and still did not report it "
                          + "after its workflowVisibilityDelay had passed - if that BPMS answers "
                          + "from a read model, its exporter is behind or has stopped.")
                          .formatted(location.hintedAdapterId())
                      : ""));
    }
    return location;

  }


  /**
   * The tail of a "no BPMS knows this workflow" message: the causes which really
   * apply.
   * <p>
   * A BPMS answering from an eventually consistent read model gets two more, and both
   * are about the same window. VanillaBP does not wait that window out here - phase one
   * asks once and never sleeps (decision 27 in the repository's DECISIONS.md) - and it
   * only knows that the workflow is worth waiting for where something told it so: it
   * started the workflow itself, or a delivery for it arrived. On a cluster that
   * knowledge sits on the node it happened on, unless the application shares its
   * {@code WorkflowAdapterCache}, which is the difference between "retry in a second"
   * and "this fails on two nodes out of three".
   *
   * @return The cause list, ending in a full stop
   */
  private String likelyCausesOfUnknownWorkflow() {

    final var eventuallyConsistent = adapterProcessServices
        .stream()
        .anyMatch(adapter -> {
          final var delay = adapter.workflowVisibilityDelay();
          return (delay != null) && delay.isWaiting();
        });
    return eventuallyConsistent
        ? "the workflow was never started, was started through another system, already ended long "
            + "ago, or was started so recently that the BPMS has not made it searchable yet - and "
            + "if another node of this application started it, only a WorkflowAdapterCache shared "
            + "between the nodes tells this node to expect it. Retrying the business operation is "
            + "what the last two causes need."
        : "the workflow was never started, was started through another system, or already ended "
            + "long ago.";

  }

  private String workflowUnknownMessage(
      final Object aggregateId,
      final String adapterId,
      final String subjectOfRead,
      final String historyContext) {

    return ("The adapter '%s' cannot provide %s of the workflow of aggregate '%s' (BPMN process "
        + "'%s' of workflow module '%s'%s)! The BPMS reported the workflow as known but has no "
        + "data for it - for BPMS cleaning up history this means the retention period has "
        + "passed; for eventually consistent BPMS it may also mean the data is not yet "
        + "visible.")
        .formatted(
            adapterId,
            subjectOfRead,
            aggregateId,
            bpmnProcessId,
            workflowModuleId,
            historyContext == null
                ? ""
                : ", history context '%s'".formatted(historyContext));

  }

  /**
   * Says that an operation the application asked for will not happen: the store found
   * an identical one still waiting for its dispatch and discarded this one.
   * <p>
   * The message names both causes because nothing can tell them apart here. A
   * redelivered dispatch of a call which was recorded already loses nothing; a second,
   * legitimate operation of the same key loses everything, and the workflow waits for
   * something which will never arrive.
   * <p>
   * What the message asks for depends on what the running activation is, because that
   * decides which repetitions are already told apart. Inside an invocation of an adapter
   * which names its activations, multi-instance siblings carry different keys and only a
   * caller repeating itself WITHIN one activation can collide (see decision 23 in the
   * repository's DECISIONS.md). Outside one - a REST endpoint, an adapter reporting no
   * activation - the correlation id is still the only thing left to vary.
   *
   * Counted as well as logged, because a line in a log is found by somebody already
   * looking. The counter carries the operation, so a discarded correlation and a
   * discarded start can be alarmed on separately.
   *
   * @param operation The operation which was dropped
   * @param subject What was dropped, named the way the caller would recognise it
   */
  private void reportDiscardedSchedule(
      final PhaseOperation operation,
      final String subject) {

    metrics.outboxScheduleDiscarded(operation.name());

    final var activation = io.vanillabp.integration.spi.RunningActivation.current();
    if (activation == null) {
      log.warn(
          """
              An operation of the same idempotency key is still waiting for its dispatch, so {} was \
              NOT planned! Either this is a redelivery of a call VanillaBP recorded before - then \
              nothing is lost - or it is a second, legitimate operation which lost against the first \
              one, and the BPMS will never see it. Nothing here can tell the two apart. This \
              operation was planned outside any activation of the BPMS - from a REST endpoint, or \
              through an adapter which does not name its activations - so a repeating scope has to \
              vary the correlation id per round or element, and a caller which repeats itself has to \
              keep an idempotency of its own.""",
          subject);
      return;
    }
    log.warn(
        """
            An operation of the same idempotency key is still waiting for its dispatch, so {} was \
            NOT planned! Either this is a redelivery of a call VanillaBP recorded before - then \
            nothing is lost - or it is a second, legitimate operation which lost against the first \
            one, and the BPMS will never see it. Nothing here can tell the two apart. The key names \
            activation '{}', so this is NOT a multi-instance sibling of another one: siblings carry \
            different activations. What collides here is one activation asking twice, which needs an \
            idempotency of its own or a correlation id which varies per round.""",
        subject,
        activation);

  }

  /**
   * Runs a phase-two operation and lets the adapter classify a failure:
   * the outbox repeats what may succeed on the next attempt - a concurrency conflict
   * is the case this exists for - while a failure repeating cannot fix blocks the
   * entry right away.
   *
   * @param adapter The adapter executing the operation
   * @param operationDescription What was attempted, for the message
   * @param operation The phase-two call
   */
  private void runPhaseTwo(
      final MigratableProcessService<A> adapter,
      final String operationDescription,
      final Runnable operation) {

    try {
      operation.run();
    } catch (final RuntimeException e) {
      if (adapter.isPhaseTwoFailureRepeatable(e)) {
        throw e;
      }
      throw new io.vanillabp.integration.spi.PhaseTwoPermanentFailure(
          """
              Phase two of %s failed, and adapter '%s' says that repeating it cannot help. The \
              outbox entry is blocked instead of being retried - look at the cause, fix what it \
              names, and remove the entry."""
              .formatted(operationDescription, adapter.getAdapterId()), e);
    }

  }

}
