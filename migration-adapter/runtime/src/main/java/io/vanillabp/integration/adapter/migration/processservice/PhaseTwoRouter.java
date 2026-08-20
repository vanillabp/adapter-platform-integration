package io.vanillabp.integration.adapter.migration.processservice;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOperation;
import io.vanillabp.integration.spi.PhaseTwoOperationRegistry;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TransactionRunner;

/**
 * Core-owned router dispatching {@link PhaseTwoCall}s scheduled via
 * {@link PhaseTwoOutbox} to the {@link MigrationProcessService} of the workflow
 * module/BPMN process:
 *
 * <pre>
 * PhaseTwoOutbox (store)
 *     --&gt; PhaseTwoRouter.dispatch(call)
 *         --&gt; MigrationProcessService (adapter selection)
 *             --&gt; MigratableProcessService (BPMS adapter)
 * </pre>
 *
 * The platform integration registers every process-service bean at bean-creation
 * time. The serialized (String) workflow-aggregate ID of an outbox entry is
 * converted back into the aggregate's ID type by the process service itself
 * ({@link MigrationProcessService#convertAggregateId} - the ID type comes from the
 * aggregate's persistence support), so the conversion happens exactly once, here.
 * <p>
 * WHICH operations exist is not hardcoded here: the router owns a
 * {@link PhaseTwoOperationRegistry} and registers the dispatch of VanillaBP's core
 * operations into it while it is built. An extension registers its own operations
 * in the same registry (see {@link #getOperations()}) and receives their calls in
 * its own {@link io.vanillabp.integration.spi.PhaseTwoOperationDispatch} - the
 * process-service routing below applies to core operations only.
 */
public final class PhaseTwoRouter {

  private record RegistrationKey(
                                 String workflowModuleId,
                                 String bpmnProcessId) {
  }

  private final Map<RegistrationKey, MigrationProcessService<?>> registrations = new ConcurrentHashMap<>();

  private final PhaseTwoOperationRegistry operations;

  /**
   * Provides the transaction (and whatever else the platform needs, e.g. an active
   * CDI request context on Quarkus) the dispatch runs in, or <code>null</code> if
   * the platform's outbox implementations bring their own (Spring Boot: gruelbox
   * dispatches inside a transaction it manages).
   */
  private final TransactionRunner transactionRunner;

  /**
   * What the application counts about its outbox (story 92). Handed in by the
   * platform integration;
   * {@link io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics#NONE}
   * for an application without a metrics backend.
   */
  private volatile io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics metrics = io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.NONE;

  /**
   * @param metrics What to count dispatches into, never <code>null</code>
   */
  public void setMetrics(
      final io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics metrics) {

    this.metrics = metrics == null
        ? io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.NONE
        : metrics;

  }

  public PhaseTwoRouter() {

    this(new PhaseTwoOperationRegistry(), null);

  }

  /**
   * @param operations The registry to register the core operations in and to
   *        resolve dispatched operations from
   */
  public PhaseTwoRouter(
      final PhaseTwoOperationRegistry operations) {

    this(operations, null);

  }

  /**
   * @param transactionRunner Provides the transaction dispatching runs in, see
   *        {@link #transactionRunner}
   */
  public PhaseTwoRouter(
      final TransactionRunner transactionRunner) {

    this(new PhaseTwoOperationRegistry(), transactionRunner);

  }

  /**
   * @param operations The registry to register the core operations in and to
   *        resolve dispatched operations from
   * @param transactionRunner Provides the transaction dispatching runs in, see
   *        {@link #transactionRunner}
   */
  public PhaseTwoRouter(
      final PhaseTwoOperationRegistry operations,
      final TransactionRunner transactionRunner) {

    this.operations = operations;
    this.transactionRunner = transactionRunner;
    registerCoreOperations();

  }

  /**
   * The registry of phase-two operations - extensions register their own
   * operations here (the platform integrations offer it as a bean).
   *
   * @return The operation registry used by this router
   */
  public PhaseTwoOperationRegistry getOperations() {

    return operations;

  }

  /**
   * Register the process service of a workflow module/BPMN process as dispatch
   * target, called by the platform integration at bean-creation time.
   *
   * @param processService The process service to route calls to
   */
  public void register(
      final MigrationProcessService<?> processService) {

    registrations.put(
        new RegistrationKey(
            processService.getWorkflowModuleId(), processService.getBpmnProcessId()),
        processService);

  }

  /**
   * Dispatch the given phase-two call to the process service registered for its
   * workflow module/BPMN process.
   *
   * @param call The phase-two call to dispatch
   * @throws IllegalStateException If no process service is registered for the
   *         call's workflow module/BPMN process (e.g. the BPMN process is no longer
   *         part of this application) - the outbox entry stays visible in the
   *         outbox store for operations
   */
  public void dispatch(
      final PhaseTwoCall call) {

    dispatch(call, false);

  }

  /**
   * Dispatch the given phase-two call to the process service registered for its
   * workflow module/BPMN process.
   *
   * @param call The phase-two call to dispatch
   * @param previouslyAttempted Whether the outbox entry was dispatched before (a
   *        recovered or retried entry) - START operations then run the
   *        re-dispatch mitigation (probe the recorded adapter's workflow
   *        awareness first; a workflow already known there consumes the entry
   *        without a second start). Stores which cannot tell pass
   *        <code>false</code> - the mitigation is best-effort, the residual
   *        at-least-once window is accepted.
   * @throws IllegalStateException If no process service is registered for the
   *         call's workflow module/BPMN process (e.g. the BPMN process is no longer
   *         part of this application) - the outbox entry stays visible in the
   *         outbox store for operations
   */
  public void dispatch(
      final PhaseTwoCall call,
      final boolean previouslyAttempted) {

    final var dispatch = operations
        .dispatchFor(call.operation())
        .orElseThrow(() -> new IllegalStateException(
            """
                Cannot dispatch outbox entry (operation '%s', aggregate ID '%s'): no phase-two \
                operation of that name is registered! Registered operations: %s. Either the entry was \
                written by a newer version of your software, or the extension contributing the \
                operation is no longer part of this application - the entry stays in the outbox store \
                for operations until the operation is available again or the entry is removed."""
                .formatted(
                    call.operation(),
                    call.workflowAggregateId(),
                    String.join(", ", operations.registeredNames()))));

    // phase two runs on the outbox dispatcher's own thread and calls back into the
    // application (the aggregate has to be loaded to build what the BPMS is told).
    // Whatever that needs - a transaction, an active CDI request context - is
    // VanillaBP's to provide here, once for every outbox implementation, instead of
    // in each of them. Which transaction it is, belongs to the aggregate of the entry
    // (story 70): an application storing this aggregate in a system of its own gets
    // its own runner here as well, and an entry of an extension - which routes to no
    // process service - gets the platform's.
    final var runner = runnerFor(call);
    metrics.outboxDispatchStarted(call.operation(), previouslyAttempted);
    // story 92: whatever the dispatch logs - and a broken BPMS connection logs a lot -
    // names the workflow it belongs to, exactly like a task delivery does
    try (var ignored = io.vanillabp.integration.adapter.migration.observability.DeliveryMdc
        .ofPhaseTwoDispatch(
            call.adapterId(),
            call.workflowModuleId(),
            call.bpmnProcessId(),
            call.workflowAggregateId())) {
      if (runner == null) {
        dispatch.dispatch(call, previouslyAttempted);
        return;
      }
      runner.requireTransaction(() -> {
        dispatch.dispatch(call, previouslyAttempted);
        return null;
      });
    } catch (final RuntimeException e) {
      metrics
          .outboxDispatchFailed(
              call.operation(),
              io.vanillabp.integration.spi.PhaseTwoPermanentFailure.isPermanent(e));
      throw e;
    }

  }

  /**
   * The runner providing the transaction of a dispatch: the one serving the aggregate
   * of the call where a process service is registered for it, the platform's otherwise.
   *
   * @param call The call about to be dispatched
   * @return The runner or <code>null</code> if none is available at all (an outbox
   *         dispatching inside a transaction of its own, e.g. gruelbox on Spring Boot)
   */
  private TransactionRunner runnerFor(
      final PhaseTwoCall call) {

    final var processService = registrations
        .get(new RegistrationKey(call.workflowModuleId(), call.bpmnProcessId()));
    if (processService == null) {
      return transactionRunner;
    }
    final var resolved = processService.getTransactionRunner(transactionRunner);
    return resolved != null
        ? resolved
        : transactionRunner;

  }

  /**
   * Registers the dispatch of VanillaBP's core operations: each of them routes to
   * the process service of the call's workflow module and BPMN process.
   */
  private void registerCoreOperations() {

    operations
        .registerCoreOperation(
            PhaseTwoOperation.START_WORKFLOW,
            (
                call,
                previouslyAttempted) -> withProcessService(
                    call,
                    (
                        processService,
                        workflowAggregateId) -> processService
                            .startWorkflowPhaseTwo(
                                workflowAggregateId,
                                call.adapterId(),
                                previouslyAttempted)));

    operations
        .registerCoreOperation(
            PhaseTwoOperation.COMPLETE_TASK,
            (
                call,
                previouslyAttempted) -> withProcessService(
                    call,
                    (
                        processService,
                        workflowAggregateId) -> processService
                            .completeTaskPhaseTwo(
                                workflowAggregateId,
                                call.args().get(PhaseTwoCall.ARG_TASK_ID))));

    operations
        .registerCoreOperation(
            PhaseTwoOperation.CANCEL_TASK,
            (
                call,
                previouslyAttempted) -> withProcessService(
                    call,
                    (
                        processService,
                        workflowAggregateId) -> processService
                            .cancelTaskPhaseTwo(
                                workflowAggregateId,
                                call.args().get(PhaseTwoCall.ARG_TASK_ID),
                                call.args().get(PhaseTwoCall.ARG_BPMN_ERROR_CODE))));

    operations
        .registerCoreOperation(
            PhaseTwoOperation.COMPLETE_USER_TASK,
            (
                call,
                previouslyAttempted) -> withProcessService(
                    call,
                    (
                        processService,
                        workflowAggregateId) -> processService
                            .completeUserTaskPhaseTwo(
                                workflowAggregateId,
                                call.args().get(PhaseTwoCall.ARG_TASK_ID))));

    operations
        .registerCoreOperation(
            PhaseTwoOperation.CANCEL_USER_TASK,
            (
                call,
                previouslyAttempted) -> withProcessService(
                    call,
                    (
                        processService,
                        workflowAggregateId) -> processService
                            .cancelUserTaskPhaseTwo(
                                workflowAggregateId,
                                call.args().get(PhaseTwoCall.ARG_TASK_ID),
                                call.args().get(PhaseTwoCall.ARG_BPMN_ERROR_CODE))));

    operations
        .registerCoreOperation(
            PhaseTwoOperation.CORRELATE_MESSAGE,
            (
                call,
                previouslyAttempted) -> withProcessService(
                    call,
                    (
                        processService,
                        workflowAggregateId) -> processService
                            .correlateMessagePhaseTwo(
                                workflowAggregateId,
                                call.args().get(PhaseTwoCall.ARG_MESSAGE_NAME),
                                call.args().get(PhaseTwoCall.ARG_CORRELATION_ID))));

    operations
        .registerCoreOperation(
            PhaseTwoOperation.AGGREGATE_CHANGED,
            (
                call,
                previouslyAttempted) -> withProcessService(
                    call,
                    (
                        processService,
                        workflowAggregateId) -> processService
                            .aggregateChangedPhaseTwo(
                                workflowAggregateId,
                                call.args().get(PhaseTwoCall.ARG_TASK_ID))));

    operations
        .registerCoreOperation(
            PhaseTwoOperation.SEND_SIGNAL,
            (
                call,
                previouslyAttempted) -> {
              // a broadcast is not about one workflow: no aggregate ID is converted
              // and no process service has to hold an aggregate - only the routing
              // by (module, process) applies
              final var processService = registrations
                  .get(new RegistrationKey(call.workflowModuleId(), call.bpmnProcessId()));
              if (processService == null) {
                throw new IllegalStateException(
                    """
                        Cannot dispatch outbox entry (operation '%s', signal '%s'): BPMN process '%s' of \
                        workflow module '%s' is not (or no longer) part of this application! If the \
                        process was removed on purpose, remove the entry from the outbox store - it \
                        stays visible there for operations."""
                        .formatted(
                            call.operation(),
                            call.args().get(PhaseTwoCall.ARG_SIGNAL_NAME),
                            call.bpmnProcessId(),
                            call.workflowModuleId()));
              }
              processService
                  .sendSignalPhaseTwo(call.args().get(PhaseTwoCall.ARG_SIGNAL_NAME), call.adapterId());
            });

    operations
        .registerCoreOperation(
            PhaseTwoOperation.START_WORKFLOW_BY_MESSAGE,
            (
                call,
                previouslyAttempted) -> withProcessService(
                    call,
                    (
                        processService,
                        workflowAggregateId) -> processService
                            .startWorkflowByMessagePhaseTwo(
                                workflowAggregateId,
                                call.args().get(PhaseTwoCall.ARG_MESSAGE_NAME),
                                call.adapterId(),
                                previouslyAttempted)));

  }

  /**
   * Resolves the process service of the call's workflow module and BPMN process,
   * converts the serialized aggregate ID and hands both to the given action - the
   * shared part of every core operation's dispatch.
   *
   * @param call The call being dispatched
   * @param action What to do with the process service and the converted aggregate
   *        ID
   * @throws IllegalStateException If no process service is registered for the
   *         call's workflow module/BPMN process
   */
  private void withProcessService(
      final PhaseTwoCall call,
      final BiConsumer<MigrationProcessService<?>, Object> action) {

    final var processService = registrations
        .get(new RegistrationKey(call.workflowModuleId(), call.bpmnProcessId()));
    if (processService == null) {
      throw new IllegalStateException(
          """
              Cannot dispatch outbox entry (operation '%s', aggregate ID '%s'): BPMN process '%s' of \
              workflow module '%s' is not (or no longer) part of this application! If the process was \
              removed on purpose, remove the entry from the outbox store - it stays visible there for \
              operations."""
              .formatted(
                  call.operation(),
                  call.workflowAggregateId(),
                  call.bpmnProcessId(),
                  call.workflowModuleId()));
    }

    action
        .accept(
            processService,
            processService.convertAggregateId(call.workflowAggregateId()));

  }

}
