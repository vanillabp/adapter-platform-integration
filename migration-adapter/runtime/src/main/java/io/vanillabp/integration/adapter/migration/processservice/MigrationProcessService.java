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

    final var location = WorkflowLocator.locate(
        adapterProcessServices,
        adapter -> adapter.awarenessOfWorkflow(aggregateId),
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
              + "in prioritized order: %s)! Likely causes: the workflow was never started, was "
              + "started through another system, or already ended long ago. To START a workflow "
              + "by a message use startWorkflowByMessage instead.")
              .formatted(subject, messageName, prioritizedAdapters));
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

    final var location = WorkflowLocator.locate(
        adapterProcessServices,
        adapter -> adapter.awarenessOfWorkflow(workflowAggregateId),
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

    adapter.startWorkflowByMessagePhaseTwo(
        workflowModuleId, bpmnProcessId, aggregatePersistenceSupport, workflowAggregateId, messageName);

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

    final var location = WorkflowLocator.locate(
        adapterProcessServices,
        adapter -> awarenessProbe.probe(adapter, aggregateId),
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

    final var location = WorkflowLocator.locate(
        adapterProcessServices,
        adapter -> awarenessProbe.probe(adapter, workflowAggregateId),
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
