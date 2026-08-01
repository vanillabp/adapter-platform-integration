package io.vanillabp.integration.adapter.migration.processservice;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.transaction.TransactionRunner;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskHandler;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.spi.service.TaskException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
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

  private final AggregatePersistenceAware<A> aggregatePersistenceSupport;

  /**
   * The type of the aggregate's ID property or <code>null</code> if not determinable
   * (custom persistence owning the serialized form). Determined once at construction
   * and validated to round-trip losslessly through the outbox's String serialization
   * (see {@link AggregateIdRoundTrip}).
   */
  private final Class<?> aggregateIdType;

  /**
   * Resolves the outbox used to schedule phase two of a two-phase workflow start.
   * Provided by the platform integration; may be <code>null</code> in tests - if an
   * adapter reporting
   * {@link MigratableProcessService#needsTwoPhaseCommitForStartingWorkflows()} is
   * first-priority, {@link #validatePhaseTwoOutboxAtStartup()} fails the startup
   * with a guiding message when no outbox can be resolved.
   */
  private final PhaseTwoOutboxResolver phaseTwoOutboxResolver;

  /**
   * The outbox resolved for this process service's aggregate,
   * <code>null</code> until resolved (at startup via
   * {@link #validatePhaseTwoOutboxAtStartup()} or lazily as backstop).
   */
  private volatile PhaseTwoOutbox phaseTwoOutbox;

  public MigrationProcessService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceSupport,
      final List<MigratableProcessService<A>> processServices,
      final PhaseTwoOutboxResolver phaseTwoOutboxResolver) {

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
    this.phaseTwoOutboxResolver = phaseTwoOutboxResolver;

    // startup check: the aggregate's ID has to round-trip losslessly through the
    // outbox's String serialization (fails with a guiding message otherwise); a
    // null ID type means a custom persistence layer owns the serialized form
    this.aggregateIdType = aggregatePersistenceSupport.getAggregateIdType();
    AggregateIdRoundTrip.validateIdTypeConvertible(workflowAggregateClass, aggregateIdType);

  }

  public boolean needsTwoPhaseCommitForStartingWorkflows() {

    return adapterProcessServices
        .getFirst()
        .needsTwoPhaseCommitForStartingWorkflows();

  }

  /**
   * Validates AT STARTUP that an outbox is available if the first-priority adapter
   * requires a two-phase commit for starting workflows - a configuration defect must
   * not surface first at runtime. If the first-priority adapter does not require a
   * two-phase commit, nothing is resolved and nothing materializes (an application
   * using only embedded BPMS must not be forced to have an outbox store). Called by
   * the platform integration once the application context is ready (not
   * mid-bean-construction, so no persistence infrastructure is materialized early).
   *
   * @throws IllegalStateException If an outbox is required but none can be resolved,
   *           naming the remedies
   */
  public void validatePhaseTwoOutboxAtStartup() {

    if (!needsTwoPhaseCommitForStartingWorkflows()) {
      return;
    }
    if (resolvePhaseTwoOutbox() == null) {
      throw new IllegalStateException(
          buildNoOutboxMessage(
              adapterProcessServices
                  .getFirst()
                  .getAdapterId()));
    }

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
   * @param transactionRunner The platform's transaction runner
   * @return The outcome the adapter maps to the BPMS
   */
  public WorkflowTaskOutcome executeWorkflowTask(
      final WorkflowTaskHandler handler,
      final TaskInvocationContext context,
      final TransactionRunner transactionRunner) {

    final Supplier<WorkflowTaskOutcome> transactionalWork = () -> {
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
        return handler.isAsynchronousTask()
            ? WorkflowTaskOutcome.completionPending()
            : WorkflowTaskOutcome.completed();
      } catch (final TaskException taskException) {
        // the restored V1 contract: a TaskException is a BPMN error, not a
        // failure - the aggregate changes are persisted and the transaction
        // commits (V1 applications used @Transactional(noRollbackFor =
        // TaskException.class) for exactly this)
        aggregatePersistenceSupport.save(workflowAggregate);
        return WorkflowTaskOutcome.bpmnError(taskException.getErrorCode(), taskException.getErrorName());
      }
    };

    return context.runInCurrentTransaction()
        ? transactionRunner.inCurrent(transactionalWork)
        : transactionRunner.requireNew(transactionalWork);

  }

  private PhaseTwoOutbox resolvePhaseTwoOutbox() {

    if ((phaseTwoOutbox == null) && (phaseTwoOutboxResolver != null)) {
      phaseTwoOutbox = phaseTwoOutboxResolver.resolveFor(workflowAggregateClass);
    }
    return phaseTwoOutbox;

  }

  private String buildNoOutboxMessage(
      final String adapterId) {

    return """
        Adapter '%s' requires a two-phase commit for starting workflows of BPMN process '%s' \
        of workflow module '%s', but no PhaseTwoOutbox is available for aggregate '%s'! \
        To solve this either
        %s
        - define your own bean implementing io.vanillabp.integration.spi.PhaseTwoOutbox \
        (assign it to specific aggregates via a io.vanillabp.integration.spi.PhaseTwoOutboxAware bean)."""
        .formatted(
            adapterId,
            bpmnProcessId,
            workflowModuleId,
            workflowAggregateClass.getName(),
            phaseTwoOutboxResolver == null
                ? "- provide a PhaseTwoOutboxResolver (platform integration), or"
                : phaseTwoOutboxResolver.remediesDescription());

  }

  public A startWorkflow(
      final A workflowAggregate) {

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
              the start's idempotency key - assign it before calling startWorkflow or use a \
              generated ID which is assigned on save."""
              .formatted(workflowAggregateClass.getName()));
    }

    final var adapter = adapterProcessServices
        .getFirst();

    adapter.startWorkflowPhaseOne(workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, attachedAggregate);

    if (adapter.needsTwoPhaseCommitForStartingWorkflows()) {
      // backstop only: the outbox was already resolved and validated at startup
      // (validatePhaseTwoOutboxAtStartup) - this fires only if that was skipped
      final var outbox = resolvePhaseTwoOutbox();
      if (outbox == null) {
        throw new IllegalStateException(
            buildNoOutboxMessage(adapter.getAdapterId()));
      }
      outbox.scheduleStartWorkflow(
          workflowModuleId,
          bpmnProcessId,
          aggregateId,
          adapter.getAdapterId());
    }

    return attachedAggregate;

  }

  /**
   * Executes phase two of starting a workflow, dispatched by the
   * {@link PhaseTwoRouter} after the local transaction of
   * {@link #startWorkflow(Object)} was committed. The adapter elected in phase one
   * was persisted with the outbox entry and is used here - there is no re-election
   * from the then-current priorities.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (in its original type)
   * @param adapterId The ID of the adapter elected in phase one
   */
  public void startWorkflowPhaseTwo(
      final Object workflowAggregateId,
      final String adapterId) {

    final var adapter = adapterProcessServices
        .stream()
        .filter(processService -> processService.getAdapterId().equals(adapterId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            """
                Cannot execute phase two of starting the workflow of aggregate '%s': adapter '%s' is \
                not (or no longer) configured for BPMN process '%s' of workflow module '%s'! The \
                outbox entry is stale - the adapter was probably removed from the configuration \
                (property 'vanillabp.prioritized-adapters' or its module-/workflow-level \
                overrides) after the entry was scheduled. Restore the adapter's configuration or \
                remove the entry from the outbox store."""
                .formatted(
                    workflowAggregateId,
                    adapterId,
                    bpmnProcessId,
                    workflowModuleId)));

    adapter.startWorkflowPhaseTwo(workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, workflowAggregateId);

  }

}
