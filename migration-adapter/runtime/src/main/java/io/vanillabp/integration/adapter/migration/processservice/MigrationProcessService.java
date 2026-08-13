package io.vanillabp.integration.adapter.migration.processservice;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.transaction.TransactionRunner;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskHandler;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
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

  /**
   * The process services of every adapter the workflow module is DEPLOYED to (the
   * union of story 27), which is more than the prioritized adapters of this process
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

  /**
   * The election for operations on existing workflows (see
   * {@link WorkflowLocator}).
   */
  private final WorkflowLocator workflowLocator;

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

  public MigrationProcessService(
      final String workflowModuleId,
      final String bpmnProcessId,
      final Class<A> workflowAggregateClass,
      final MigrationAdapterProperties properties,
      final AggregatePersistenceAware<A> aggregatePersistenceSupport,
      final List<MigratableProcessService<A>> processServices,
      final PhaseTwoOutboxResolver phaseTwoOutboxResolver,
      final WorkflowAdapterCache workflowAdapterCache) {

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
   * Loads the workflow aggregate by its serialized ID within the CALLER's
   * transaction - used to resolve aggregate attributes referenced by BPMN
   * expressions of embedded BPMS (the expression evaluates inside an engine
   * transaction the aggregate has to join).
   *
   * @param serializedAggregateId The aggregate ID in serialized form
   * @return The aggregate or <code>null</code>
   */
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
   * @param transactionRunner The platform's transaction runner
   * @param rollbackRuleRemedies How a rollback rule excluding a {@link TaskException} is
   *          written on this platform, named by the failure of the rollback-only check
   * @return The outcome the adapter maps to the BPMS
   */
  public WorkflowTaskOutcome executeWorkflowTask(
      final WorkflowTaskHandler handler,
      final TaskInvocationContext context,
      final TransactionRunner transactionRunner,
      final List<String> rollbackRuleRemedies) {

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
        failIfRollbackOnly(handler, context, transactionRunner, rollbackRuleRemedies);
        return handler.isAsynchronousTask()
            ? WorkflowTaskOutcome.completionPending()
            : WorkflowTaskOutcome.completed();
      } catch (final TaskException taskException) {
        // the restored V1 contract: a TaskException is a BPMN error, not a
        // failure - the aggregate changes are persisted and the transaction
        // commits (V1 applications used @Transactional(noRollbackFor =
        // TaskException.class) for exactly this)
        aggregatePersistenceSupport.save(workflowAggregate);
        failIfRollbackOnly(handler, context, transactionRunner, rollbackRuleRemedies);
        return WorkflowTaskOutcome.bpmnError(taskException.getErrorCode(), taskException.getErrorName());
      }
    };

    return context.runInCurrentTransaction()
        ? transactionRunner.inCurrent(transactionalWork)
        : transactionRunner.requireNew(transactionalWork);

  }

  /**
   * Records which adapter holds the workflow of the given aggregate, for the
   * moments VanillaBP knows it without asking anybody: scheduling a start (the
   * elected adapter is decided then), phase two of that start, and every inbound
   * delivery (a task, a user task, the end of a workflow, a start the BPMS
   * performed).
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

    // the workflow belongs to this adapter from now on, which the next operation on
    // it has to know BEFORE phase two ran: on a remote BPMS the start is dispatched
    // asynchronously, so an operation following right away would otherwise find no
    // hint at all and fail instead of waiting for the BPMS to catch up
    rememberWorkflowAdapter(aggregateId, adapter.getAdapterId());

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

    startWorkflowPhaseTwo(workflowAggregateId, adapterId, false);

  }

  /**
   * Executes phase two of starting a workflow - see
   * {@link #startWorkflowPhaseTwo(Object, String)}. If the outbox entry was
   * dispatched before (a recovered or retried entry), the re-dispatch mitigation
   * probes {@link MigratableProcessService#awarenessOfWorkflowForRedispatch} on
   * the recorded adapter FIRST: a workflow already known there means the previous
   * dispatch already started it - the entry is consumed without starting a second
   * instance. The residual at-least-once window (a crash between the remote start
   * and marking the entry done, before any awareness lag caught up) remains and
   * is ACCEPTED - this mitigation minimizes duplicates, it does not close the
   * window.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (in its original type)
   * @param adapterId The ID of the adapter elected in phase one
   * @param previouslyAttempted Whether the outbox entry was dispatched before
   */
  public void startWorkflowPhaseTwo(
      final Object workflowAggregateId,
      final String adapterId,
      final boolean previouslyAttempted) {

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

    if (previouslyAttempted && skipRedispatchedStart(adapter, workflowAggregateId, "starting the workflow")) {
      return;
    }
    adapter.startWorkflowPhaseTwo(workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, workflowAggregateId);
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

    final var awareness = adapter.awarenessOfWorkflowForRedispatch(aggregatePersistenceSupport, workflowAggregateId);
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
   * Completes an asynchronous task (a <code>&#64;WorkflowTask</code> method with a
   * <code>&#64;TaskId</code> parameter returned without completing). The aggregate
   * is saved, the BPMS holding the task is located by probing the prioritized
   * adapters ({@link WorkflowLocator}), and the completion runs in phase one
   * (embedded BPMS - entirely within the caller's transaction) or is scheduled
   * through the phase-two outbox (remote BPMS - after a non-advancing phase-one
   * check).
   *
   * @param workflowAggregate The workflow aggregate
   * @param taskId The task's ID (as reported to the <code>&#64;TaskId</code>
   *        parameter)
   * @return The attached workflow aggregate
   */
  public A completeTask(
      final A workflowAggregate,
      final String taskId) {

    return executeTaskOperation(
        workflowAggregate,
        taskId,
        "completing",
        (
            adapter,
            aggregateId) -> adapter.awarenessOfTask(aggregateId, taskId),
        (
            adapter,
            attachedAggregate) -> adapter
                .completeTaskPhaseOne(
                    workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, attachedAggregate, taskId),
        (
            outbox,
            aggregateId) -> outbox
                .scheduleCompleteTask(workflowModuleId, bpmnProcessId, aggregateId, taskId));

  }

  /**
   * Cancels an asynchronous task by BPMN error - same flow as
   * {@link #completeTask(Object, String)}.
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

    return executeTaskOperation(
        workflowAggregate,
        taskId,
        "canceling",
        (
            adapter,
            aggregateId) -> adapter.awarenessOfTask(aggregateId, taskId),
        (
            adapter,
            attachedAggregate) -> adapter
                .cancelTaskPhaseOne(
                    workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, attachedAggregate, taskId,
                    bpmnErrorCode),
        (
            outbox,
            aggregateId) -> outbox
                .scheduleCancelTask(workflowModuleId, bpmnProcessId, aggregateId, taskId, bpmnErrorCode));

  }

  @FunctionalInterface
  private interface PhaseOneAction<A> {

    void run(
        MigratableProcessService<A> adapter,
        A attachedAggregate);

  }

  @FunctionalInterface
  private interface OutboxAction {

    boolean schedule(
        PhaseTwoOutbox outbox,
        Object aggregateId);

  }

  /**
   * Completes a USER task - same flow as {@link #completeTask(Object, String)}
   * but probing {@code awarenessOfUserTask} and executing the user-task SPI
   * methods (user-task IDs live in a different namespace than service-task IDs).
   *
   * @param workflowAggregate The workflow aggregate
   * @param taskId The user task's ID
   * @return The attached workflow aggregate
   */
  public A completeUserTask(
      final A workflowAggregate,
      final String taskId) {

    return executeTaskOperation(
        workflowAggregate,
        taskId,
        "completing user",
        (
            adapter,
            aggregateId) -> adapter.awarenessOfUserTask(aggregateId, taskId),
        (
            adapter,
            attachedAggregate) -> adapter
                .completeUserTaskPhaseOne(
                    workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, attachedAggregate, taskId),
        (
            outbox,
            aggregateId) -> outbox
                .scheduleCompleteUserTask(workflowModuleId, bpmnProcessId, aggregateId, taskId));

  }

  /**
   * Cancels a USER task by BPMN error - same flow as
   * {@link #cancelTask(Object, String, String)}.
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

    return executeTaskOperation(
        workflowAggregate,
        taskId,
        "canceling user",
        (
            adapter,
            aggregateId) -> adapter.awarenessOfUserTask(aggregateId, taskId),
        (
            adapter,
            attachedAggregate) -> adapter
                .cancelUserTaskPhaseOne(
                    workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, attachedAggregate, taskId,
                    bpmnErrorCode),
        (
            outbox,
            aggregateId) -> outbox
                .scheduleCancelUserTask(workflowModuleId, bpmnProcessId, aggregateId, taskId, bpmnErrorCode));

  }

  /**
   * Executes phase two of completing a user task - see
   * {@link #completeTaskPhaseTwo(Object, String)}.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (original type)
   * @param taskId The user task's ID
   */
  public void completeUserTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId) {

    executeTaskPhaseTwo(
        workflowAggregateId,
        taskId,
        "completing user",
        (
            adapter,
            aggregateId) -> adapter.awarenessOfUserTask(aggregateId, taskId),
        adapter -> adapter
            .completeUserTaskPhaseTwo(
                workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, workflowAggregateId, taskId));

  }

  /**
   * Executes phase two of canceling a user task - see
   * {@link #cancelTaskPhaseTwo(Object, String, String)}.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (original type)
   * @param taskId The user task's ID
   * @param bpmnErrorCode The error code to be caught by BPMN error boundary events
   */
  public void cancelUserTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    executeTaskPhaseTwo(
        workflowAggregateId,
        taskId,
        "canceling user",
        (
            adapter,
            aggregateId) -> adapter.awarenessOfUserTask(aggregateId, taskId),
        adapter -> adapter
            .cancelUserTaskPhaseTwo(
                workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, workflowAggregateId, taskId,
                bpmnErrorCode));

  }

  /**
   * Correlates a message with the aggregate's workflow: the aggregate is saved,
   * the BPMS running the workflow is located by probing
   * {@code awarenessOfWorkflow}, and the correlation runs in phase one (embedded)
   * or is scheduled through the outbox (remote). PAYLOAD DOCTRINE: no message
   * content travels to the BPMS - only the message name and the optional
   * correlation id.
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

    final var attachedAggregate = aggregatePersistenceSupport
        .save(workflowAggregate);
    final var aggregateId = aggregatePersistenceSupport
        .getAggregateId(attachedAggregate);

    final var subject = "workflow of aggregate '%s' (BPMN process '%s' of workflow module '%s')"
        .formatted(aggregateId, bpmnProcessId, workflowModuleId);

    final var location = workflowLocator.locate(
        adapterProcessServices,
        adapter -> adapter.awarenessOfWorkflow(aggregatePersistenceSupport, aggregateId),
        aggregateId,
        subject);

    switch (location.awareness()) {
      case COMPLETED -> {
        // the workflow already ended - correlating is a no-op with warning
        log.warn(
            "Ignored correlating message '{}' with {}: adapter '{}' reports the workflow as "
                + "already completed",
            messageName,
            subject,
            location.adapter().getAdapterId());
        return attachedAggregate;
      }
      case UNKNOWN_TO_BPMS -> throw new io.vanillabp.spi.process.WorkflowNotFoundException(
          ("No configured BPMS knows the %s - message '%s' cannot be correlated (probed adapters, "
              + "in prioritized order: %s)! Likely causes: %s To START a workflow by a message "
              + "use startWorkflowByMessage instead.")
              .formatted(subject, messageName, prioritizedAdapters, likelyCausesOfUnknownWorkflow()));
      default -> {
        // ACTIVE - fall through
      }
    }

    final var adapter = location.adapter();
    adapter.correlateMessagePhaseOne(
        workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, attachedAggregate, messageName,
        correlationId);

    if (adapter.needsTwoPhaseCommitForStartingWorkflows()) {
      final var outbox = resolvePhaseTwoOutbox();
      if (outbox == null) {
        throw new IllegalStateException(
            buildNoOutboxMessage(adapter.getAdapterId()));
      }
      outbox.scheduleCorrelateMessage(workflowModuleId, bpmnProcessId, aggregateId, messageName, correlationId);
    }

    return attachedAggregate;

  }

  /**
   * Pushes a changed workflow-aggregate to the BPMS holding its workflow: the
   * aggregate is saved, the BPMS is located by probing {@code awarenessOfWorkflow},
   * and the push runs in phase one (embedded) or is scheduled through the outbox
   * (remote).
   * <p>
   * WHICH values travel is the sync model's business ({@code @SyncWithBPMS}), not
   * this method's. WHERE they land depends on the task ID: <code>null</code> means
   * the workflow's global scope, a task ID means the scope of that task instance
   * only - a task-scoped push deliberately leaves the global values as they were.
   *
   * @param workflowAggregate The workflow aggregate
   * @param taskId The ID of the task whose scope receives the values, or
   *        <code>null</code> for the workflow's global scope
   * @return The attached workflow aggregate
   */
  public A aggregateChanged(
      final A workflowAggregate,
      final String taskId) {

    final var attachedAggregate = aggregatePersistenceSupport
        .save(workflowAggregate);
    final var aggregateId = aggregatePersistenceSupport
        .getAggregateId(attachedAggregate);

    final var subject = "workflow of aggregate '%s' (BPMN process '%s' of workflow module '%s')"
        .formatted(aggregateId, bpmnProcessId, workflowModuleId);

    final var location = workflowLocator.locate(
        adapterProcessServices,
        adapter -> adapter.awarenessOfWorkflow(aggregatePersistenceSupport, aggregateId),
        aggregateId,
        subject);

    switch (location.awareness()) {
      case COMPLETED -> {
        // a workflow which ended has no variables worth updating - the aggregate is
        // saved either way, which is what the caller mainly wanted
        log.warn(
            "Ignored pushing the changed aggregate of {} to the BPMS: adapter '{}' reports the "
                + "workflow as already completed",
            subject,
            location.adapter().getAdapterId());
        return attachedAggregate;
      }
      case UNKNOWN_TO_BPMS -> throw new io.vanillabp.spi.process.WorkflowNotFoundException(
          ("No configured BPMS knows the %s - the changed aggregate cannot be pushed (probed "
              + "adapters, in prioritized order: %s)! The aggregate itself was saved. Likely "
              + "causes: %s")
              .formatted(subject, prioritizedAdapters, likelyCausesOfUnknownWorkflow()));
      default -> {
        // ACTIVE - fall through
      }
    }

    final var adapter = location.adapter();
    adapter.aggregateChangedPhaseOne(
        workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, attachedAggregate, taskId);

    if (adapter.needsTwoPhaseCommitForStartingWorkflows()) {
      final var outbox = resolvePhaseTwoOutbox();
      if (outbox == null) {
        throw new IllegalStateException(
            buildNoOutboxMessage(adapter.getAdapterId()));
      }
      outbox.scheduleAggregateChanged(workflowModuleId, bpmnProcessId, aggregateId, taskId);
    }

    return attachedAggregate;

  }

  /**
   * Executes phase two of pushing a changed workflow-aggregate - dispatch-time
   * election via probing; a workflow gone by now is a stale entry (logged,
   * consumed). The values are read from the aggregate as it is now.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (original type)
   * @param taskId The ID of the task whose scope receives the values, or
   *        <code>null</code> for the workflow's global scope
   */
  public void aggregateChangedPhaseTwo(
      final Object workflowAggregateId,
      final String taskId) {

    final var subject = "workflow of aggregate '%s' (BPMN process '%s' of workflow module '%s')"
        .formatted(workflowAggregateId, bpmnProcessId, workflowModuleId);

    final var location = workflowLocator.locate(
        adapterProcessServices,
        adapter -> adapter.awarenessOfWorkflow(aggregatePersistenceSupport, workflowAggregateId),
        workflowAggregateId,
        subject);

    switch (location.awareness()) {
      case UNKNOWN_TO_BPMS, COMPLETED -> log.warn(
          "Skipped phase two of pushing the changed aggregate of {}: the workflow is gone (stale "
              + "outbox entry); the entry is consumed",
          subject);
      default -> location
          .adapter()
          .aggregateChangedPhaseTwo(
              workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, workflowAggregateId, taskId);
    }

  }

  /**
   * Broadcasts a BPMN signal to every BPMS the workflow module is deployed to.
   * <p>
   * A signal is not addressed to a workflow, so nothing is probed and no aggregate
   * is loaded or saved. Embedded BPMS broadcast inside the caller's transaction
   * (a rollback takes the broadcast with it), remote BPMS get an outbox entry each
   * and broadcast after the commit.
   * <p>
   * Every deployed BPMS is asked, not only the first-priority one: during a
   * migration the workflows waiting for the signal are spread across them, and a
   * broadcast reaching half of them would be worse than none.
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

    final var targets = deploymentAdapterProcessServices.isEmpty()
        ? adapterProcessServices
        : deploymentAdapterProcessServices;

    RuntimeException failure = null;
    for (final var adapter : targets) {
      try {
        adapter.sendSignalPhaseOne(workflowModuleId, bpmnProcessId, signalName);
        if (adapter.needsTwoPhaseCommitForStartingWorkflows()) {
          final var outbox = resolvePhaseTwoOutbox();
          if (outbox == null) {
            throw new IllegalStateException(
                buildNoOutboxMessage(adapter.getAdapterId()));
          }
          outbox.scheduleSendSignal(workflowModuleId, bpmnProcessId, signalName, adapter.getAdapterId());
        }
      } catch (final RuntimeException e) {
        // every BPMS is asked before the first failure is reported: a broadcast
        // which stopped at the first unreachable BPMS would leave the others
        // waiting, and the outbox retries what was scheduled anyway
        log.error(
            "Broadcasting signal '{}' of workflow module '{}' to adapter '{}' failed",
            signalName,
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

  }

  /**
   * Executes phase two of broadcasting a signal, dispatched by the
   * {@link PhaseTwoRouter} after the local transaction was committed. The adapter
   * of the entry is the one the broadcast was scheduled for - there is no
   * election, a broadcast is not about a workflow.
   *
   * @param signalName The PLAIN BPMN signal name
   * @param adapterId The ID of the adapter to broadcast to
   */
  public void sendSignalPhaseTwo(
      final String signalName,
      final String adapterId) {

    final var adapter = deploymentAdapterProcessServices
        .stream()
        .filter(candidate -> candidate.getAdapterId().equals(adapterId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            """
                Cannot broadcast signal '%s' of BPMN process '%s' (workflow module '%s'): the adapter \
                '%s' the outbox entry was written for is not configured (any more)! Either restore the \
                adapter's configuration or remove the entry from the outbox store."""
                .formatted(signalName, bpmnProcessId, workflowModuleId, adapterId)));

    adapter.sendSignalPhaseTwo(workflowModuleId, bpmnProcessId, signalName);

  }

  /**
   * Executes phase two of correlating a message - dispatch-time election via
   * probing (like task operations); a workflow gone by now is a stale entry
   * (logged, consumed).
   *
   * @param workflowAggregateId The ID of the workflow aggregate (original type)
   * @param messageName The BPMN message name
   * @param correlationId The correlation id or <code>null</code>
   */
  public void correlateMessagePhaseTwo(
      final Object workflowAggregateId,
      final String messageName,
      final String correlationId) {

    final var subject = "workflow of aggregate '%s' (BPMN process '%s' of workflow module '%s')"
        .formatted(workflowAggregateId, bpmnProcessId, workflowModuleId);

    final var location = workflowLocator.locate(
        adapterProcessServices,
        adapter -> adapter.awarenessOfWorkflow(aggregatePersistenceSupport, workflowAggregateId),
        workflowAggregateId,
        subject);

    switch (location.awareness()) {
      case UNKNOWN_TO_BPMS, COMPLETED -> log.warn(
          "Skipped phase two of correlating message '{}' with {}: the workflow is gone (stale "
              + "outbox entry); the entry is consumed",
          messageName,
          subject);
      default -> location
          .adapter()
          .correlateMessagePhaseTwo(
              workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, workflowAggregateId, messageName,
              correlationId);
    }

  }

  /**
   * Starts a new workflow by a message start event - start semantics like
   * {@link #startWorkflow(Object)}: the FIRST prioritized adapter starts, its ID
   * is persisted with the outbox entry, and a workflow is started at most once
   * per aggregate.
   *
   * @param workflowAggregate The workflow aggregate
   * @param messageName The BPMN message name of the message start event
   * @return The attached workflow aggregate
   */
  public A startWorkflowByMessage(
      final A workflowAggregate,
      final String messageName) {

    final var attachedAggregate = aggregatePersistenceSupport
        .save(workflowAggregate);
    final var aggregateId = aggregatePersistenceSupport
        .getAggregateId(attachedAggregate);
    if ((aggregateId == null) || aggregateId.toString().isBlank()) {
      throw new IllegalStateException(
          ("The ID of the workflow aggregate of class '%s' is null or blank after saving! The ID "
              + "identifies the workflow in the BPMS and is part of the start's idempotency key - "
              + "assign it before calling startWorkflowByMessage or use a generated ID.")
              .formatted(workflowAggregateClass.getName()));
    }

    final var adapter = adapterProcessServices
        .getFirst();
    adapter.startWorkflowByMessagePhaseOne(
        workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, attachedAggregate, messageName);

    if (adapter.needsTwoPhaseCommitForStartingWorkflows()) {
      final var outbox = resolvePhaseTwoOutbox();
      if (outbox == null) {
        throw new IllegalStateException(
            buildNoOutboxMessage(adapter.getAdapterId()));
      }
      outbox.scheduleStartWorkflowByMessage(
          workflowModuleId, bpmnProcessId, aggregateId, messageName, adapter.getAdapterId());
    }

    rememberWorkflowAdapter(aggregateId, adapter.getAdapterId());

    return attachedAggregate;

  }

  /**
   * Executes phase two of starting a workflow by message - the adapter persisted
   * with the entry is used (start semantics, no re-election).
   *
   * @param workflowAggregateId The ID of the workflow aggregate (original type)
   * @param messageName The BPMN message name
   * @param adapterId The ID of the adapter elected in phase one
   */
  public void startWorkflowByMessagePhaseTwo(
      final Object workflowAggregateId,
      final String messageName,
      final String adapterId) {

    startWorkflowByMessagePhaseTwo(workflowAggregateId, messageName, adapterId, false);

  }

  /**
   * Executes phase two of starting a workflow by message - see
   * {@link #startWorkflowByMessagePhaseTwo(Object, String, String)}; a previously
   * attempted entry runs the same re-dispatch mitigation as
   * {@link #startWorkflowPhaseTwo(Object, String, boolean)}.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (original type)
   * @param messageName The BPMN message name
   * @param adapterId The ID of the adapter elected in phase one
   * @param previouslyAttempted Whether the outbox entry was dispatched before
   */
  public void startWorkflowByMessagePhaseTwo(
      final Object workflowAggregateId,
      final String messageName,
      final String adapterId,
      final boolean previouslyAttempted) {

    final var adapter = adapterProcessServices
        .stream()
        .filter(processService -> processService.getAdapterId().equals(adapterId))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException(
            ("Cannot execute phase two of starting the workflow of aggregate '%s' by message '%s': "
                + "adapter '%s' is not (or no longer) configured for BPMN process '%s' of workflow "
                + "module '%s'! The outbox entry is stale - restore the adapter's configuration or "
                + "remove the entry from the outbox store.")
                .formatted(workflowAggregateId, messageName, adapterId, bpmnProcessId, workflowModuleId)));

    if (previouslyAttempted && skipRedispatchedStart(
        adapter, workflowAggregateId, "starting the workflow by message '%s'".formatted(messageName))) {
      return;
    }
    adapter.startWorkflowByMessagePhaseTwo(
        workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, workflowAggregateId, messageName);
    rememberWorkflowAdapter(workflowAggregateId, adapterId);

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
   */
  private WorkflowLocator.Location<A> locateForReading(
      final Object aggregateId,
      final String subjectOfRead) {

    final var subject = "workflow of aggregate '%s' (BPMN process '%s' of workflow module '%s')"
        .formatted(aggregateId, bpmnProcessId, workflowModuleId);

    final var location = workflowLocator.locate(
        adapterProcessServices,
        adapter -> adapter.awarenessOfWorkflow(aggregatePersistenceSupport, aggregateId),
        aggregateId,
        subject);

    if (location.awareness() == WorkflowAwareness.UNKNOWN_TO_BPMS) {
      throw new io.vanillabp.spi.process.WorkflowNotFoundException(
          ("No configured BPMS knows the %s - %s cannot be determined (probed adapters, in "
              + "prioritized order: %s)! Likely causes: the workflow was never started, was "
              + "started through another system, or its history was already cleaned up in the "
              + "BPMS.")
              .formatted(subject, subjectOfRead, prioritizedAdapters));
    }
    return location;

  }


  /**
   * The tail of a "no BPMS knows this workflow" message: the causes which really
   * apply. A remote BPMS answering from an eventually consistent read model gets one
   * more, because "not visible yet" is a state a workflow can be in without anything
   * being wrong - VanillaBP already waited out the window that BPMS asked for (see
   * {@code WorkflowVisibilityDelay}) where it had a reason to expect the workflow
   * there.
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
            + "ago, or was started so recently that the BPMS has not made it searchable yet."
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

  @FunctionalInterface
  private interface TaskAwarenessProbe<A> {

    io.vanillabp.integration.adapter.spi.WorkflowAwareness probe(
        MigratableProcessService<A> adapter,
        Object workflowAggregateId);

  }

  private A executeTaskOperation(
      final A workflowAggregate,
      final String taskId,
      final String operationDescription,
      final TaskAwarenessProbe<A> awarenessProbe,
      final PhaseOneAction<A> phaseOne,
      final OutboxAction outboxAction) {

    // persist changes made before completing/canceling - identical to
    // startWorkflow the aggregate rides the caller's transaction
    final var attachedAggregate = aggregatePersistenceSupport
        .save(workflowAggregate);
    final var aggregateId = aggregatePersistenceSupport
        .getAggregateId(attachedAggregate);

    final var subject = "task '%s' of workflow aggregate '%s' (BPMN process '%s' of workflow module '%s')"
        .formatted(taskId, aggregateId, bpmnProcessId, workflowModuleId);

    final var location = workflowLocator.locate(
        adapterProcessServices,
        adapter -> awarenessProbe.probe(adapter, aggregateId),
        aggregateId,
        subject);

    switch (location.awareness()) {
      case COMPLETED -> {
        // idempotent completion: the task is already done - a no-op with warning
        log.warn(
            "Ignored {} {}: adapter '{}' reports it as already completed",
            operationDescription,
            subject,
            location.adapter().getAdapterId());
        return attachedAggregate;
      }
      case UNKNOWN_TO_BPMS -> throw new io.vanillabp.spi.process.TaskNotFoundException(
          ("No configured BPMS knows %s (probed adapters, in prioritized order: %s)! Likely "
              + "causes: the task ID is wrong or outdated, the task was already completed long "
              + "ago, or the workflow was terminated. If a BPMS was reported unavailable, this "
              + "operation would have failed differently - an unknown task is a definite answer "
              + "of all adapters.")
              .formatted(subject, prioritizedAdapters));
      default -> {
        // ACTIVE - fall through to the execution below
      }
    }

    final var adapter = location.adapter();
    phaseOne.run(adapter, attachedAggregate);

    if (adapter.needsTwoPhaseCommitForStartingWorkflows()) {
      final var outbox = resolvePhaseTwoOutbox();
      if (outbox == null) {
        throw new IllegalStateException(
            buildNoOutboxMessage(adapter.getAdapterId()));
      }
      outboxAction.schedule(outbox, aggregateId);
    }

    return attachedAggregate;

  }

  /**
   * Executes phase two of completing a task, dispatched by the
   * {@link PhaseTwoRouter} after the local transaction was committed. Unlike
   * workflow starts NO adapter was persisted with the outbox entry - the adapter is
   * elected NOW by probing (the BPMS holding the task answers).
   *
   * @param workflowAggregateId The ID of the workflow aggregate (original type)
   * @param taskId The task's ID
   */
  public void completeTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId) {

    executeTaskPhaseTwo(
        workflowAggregateId,
        taskId,
        "completing",
        (
            adapter,
            aggregateId) -> adapter.awarenessOfTask(aggregateId, taskId),
        adapter -> adapter
            .completeTaskPhaseTwo(
                workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, workflowAggregateId, taskId));

  }

  /**
   * Executes phase two of canceling a task - see
   * {@link #completeTaskPhaseTwo(Object, String)}.
   *
   * @param workflowAggregateId The ID of the workflow aggregate (original type)
   * @param taskId The task's ID
   * @param bpmnErrorCode The error code to be caught by BPMN error boundary events
   */
  public void cancelTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    executeTaskPhaseTwo(
        workflowAggregateId,
        taskId,
        "canceling",
        (
            adapter,
            aggregateId) -> adapter.awarenessOfTask(aggregateId, taskId),
        adapter -> adapter
            .cancelTaskPhaseTwo(
                workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, workflowAggregateId, taskId,
                bpmnErrorCode));

  }

  private void executeTaskPhaseTwo(
      final Object workflowAggregateId,
      final String taskId,
      final String operationDescription,
      final TaskAwarenessProbe<A> awarenessProbe,
      final java.util.function.Consumer<MigratableProcessService<A>> phaseTwo) {

    final var subject = "task '%s' of workflow aggregate '%s' (BPMN process '%s' of workflow module '%s')"
        .formatted(taskId, workflowAggregateId, bpmnProcessId, workflowModuleId);

    final var location = workflowLocator.locate(
        adapterProcessServices,
        adapter -> awarenessProbe.probe(adapter, workflowAggregateId),
        workflowAggregateId,
        subject);

    switch (location.awareness()) {
      // stale outbox entry: the task disappeared between phase one and this
      // dispatch (e.g. completed by a redelivered at-least-once job, or a boundary
      // event canceled it). The entry is consumed - throwing would retry a call
      // which can never succeed.
      case UNKNOWN_TO_BPMS, COMPLETED -> log.warn(
          "Skipped phase two of {} {}: the task is gone (stale outbox entry - it disappeared "
              + "between scheduling and dispatch); the outbox entry is consumed",
          operationDescription,
          subject);
      // BPMS_UNAVAILABLE cannot reach here (locate throws) - the outbox retries
      default -> phaseTwo.accept(location.adapter());
    }

  }

}
