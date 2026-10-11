package io.vanillabp.integration.adapter.migration.processservice;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseOperationDispatch;
import io.vanillabp.integration.spi.PhaseOperationRegistry;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TransactionRunner;

/**
 * The router, owned by the core, which hands {@link PhaseTwoCall}s planned in the
 * {@link PhaseTwoOutbox} to the {@link MigrationProcessService} of their workflow module and
 * BPMN process:
 *
 * <pre>
 * PhaseTwoOutbox (store)
 *     --&gt; PhaseTwoRouter.dispatch(call)
 *         --&gt; MigrationProcessService (adapter selection)
 *             --&gt; MigratableProcessService (BPMS adapter)
 * </pre>
 *
 * The platform integration registers every process-service bean when the beans are created.
 * The process service itself converts the serialized (String) workflow-aggregate ID of an
 * outbox entry back into the aggregate's ID type
 * ({@link MigrationProcessService#convertAggregateId}). The ID type comes from the
 * persistence support of the aggregate. So the conversion happens exactly once, here.
 * <p>
 * WHICH operations exist is not hard-coded here. The router owns a
 * {@link PhaseOperationRegistry}, and registers the dispatch of VanillaBP's core operations
 * in it while it is built. An extension registers its own operations in the same registry
 * (see {@link #getOperations()}). It receives their calls in its own
 * {@link io.vanillabp.integration.spi.PhaseOperationDispatch}. The routing to process
 * services below applies to core operations only.
 * <p>
 * Decision 2 in the repository's DECISIONS.md says why anything reaches this router at all,
 * instead of running in the caller's transaction. Decision 11 says why every dispatch is
 * wrapped in the transaction runner of ITS aggregate.
 */
// see decision 1 in the repository's DECISIONS.md
@SuppressWarnings("LombokGetterMayBeUsed")
public final class PhaseTwoRouter {

  private record RegistrationKey(
                                 String workflowModuleId,
                                 String bpmnProcessId) {
  }

  private final Map<RegistrationKey, MigrationProcessService<?>> registrations = new ConcurrentHashMap<>();

  private final PhaseOperationRegistry operations;

  /**
   * Provides the transaction in which the dispatch runs, and whatever else the platform
   * needs, for example an active CDI request context on Quarkus. It is <code>null</code> where
   * the platform hands the router none. Spring Boot hands none. There a dispatch runs in the
   * runner of its own aggregate, which {@link #runnerFor(PhaseTwoCall)} asks the process
   * service for.
   */
  private final TransactionRunner transactionRunner;

  /**
   * What the application counts about its outbox. The platform integration hands it in. It
   * is {@link io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics#NONE}
   * for an application without a metrics backend.
   */
  private volatile io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics metrics = io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.NONE;

  /**
   * Hands the router what to count into. The platform integration calls it right after the
   * router was built, because the metrics are a bean of their own and may be missing
   * altogether. A missing one is not an error. It becomes
   * {@link io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics#NONE}. So
   * a dispatch never has to ask whether anybody counts.
   *
   * @param metrics What to count dispatches into, never <code>null</code>
   */
  public void setMetrics(
      final io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics metrics) {

    this.metrics = metrics == null
        ? io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.NONE
        : metrics;

  }

  /**
   * Builds a router with a registry of its own and no transaction runner of its own. A
   * dispatch then runs in the transaction which the process service of the call resolves for
   * its aggregate (see decision 11 in the repository's DECISIONS.md). An entry which routes
   * to no process service runs without one.
   */
  public PhaseTwoRouter() {

    this(new PhaseOperationRegistry(), null);

  }

  /**
   * Builds a router on a registry which somebody else owns. So the operations which an
   * extension registered and the core's own operations end up in one place.
   *
   * @param operations The registry to register the core operations in and to
   *        resolve dispatched operations from
   */
  public PhaseTwoRouter(
      final PhaseOperationRegistry operations) {

    this(operations, null);

  }

  /**
   * Builds a router with the platform's transaction runner. It is the fallback. A dispatch
   * whose aggregate resolves a runner of its own uses that one instead.
   *
   * @param transactionRunner Provides the transaction in which the dispatch runs, see
   *        {@link #transactionRunner}
   */
  public PhaseTwoRouter(
      final TransactionRunner transactionRunner) {

    this(new PhaseOperationRegistry(), transactionRunner);

  }

  /**
   * Builds a router on a given registry, with a given transaction runner. The other three
   * constructors end up here. This is where the core operations are registered. So an
   * extension which registers its own operations later always finds the core ones
   * already there.
   *
   * @param operations The registry to register the core operations in and to
   *        resolve dispatched operations from
   * @param transactionRunner Provides the transaction in which the dispatch runs, see
   *        {@link #transactionRunner}
   */
  public PhaseTwoRouter(
      final PhaseOperationRegistry operations,
      final TransactionRunner transactionRunner) {

    this.operations = operations;
    this.transactionRunner = transactionRunner;
    registerCoreOperations();

  }

  /**
   * The registry of phase-two operations. Extensions register their own operations here (the
   * platform integrations offer it as a bean).
   *
   * @return The operation registry used by this router
   */
  public PhaseOperationRegistry getOperations() {

    return operations;

  }

  /**
   * Registers the process service of a workflow module and BPMN process as a target of the
   * dispatch. The platform integration calls it when the beans are created.
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
   * The process service of a BPMN process of a workflow module. A caller outside the
   * dispatch needs this when it has a workflow module and a BPMN process and nothing else.
   * The router is the one place where all of them are collected.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @return The process service, or <code>null</code> if this application serves no such
   *         BPMN process
   */
  public MigrationProcessService<?> processServiceOf(
      final String workflowModuleId,
      final String bpmnProcessId) {

    return registrations.get(new RegistrationKey(workflowModuleId, bpmnProcessId));

  }

  /**
   * The workflow modules and BPMN processes which this application serves, for guiding
   * messages which name what a caller could have meant.
   *
   * @return One <code>&lt;module&gt;/&lt;process&gt;</code> per registration, sorted
   */
  public java.util.List<String> registeredWorkflows() {

    return registrations
        .keySet()
        .stream()
        .map(key -> "%s/%s".formatted(key.workflowModuleId(), key.bpmnProcessId()))
        .sorted()
        .toList();

  }

  /**
   * Dispatches the given phase-two call to the process service registered for its workflow
   * module and BPMN process.
   *
   * @param call The phase-two call to dispatch
   * @throws IllegalStateException If no process service is registered for the call's
   *         workflow module and BPMN process (for example because the BPMN process is no
   *         longer part of this application). The outbox entry stays visible in the outbox
   *         store for operations
   */
  public void dispatch(
      final PhaseTwoCall call) {

    dispatch(call, false);

  }

  /**
   * Dispatches the given phase-two call to the process service registered for its workflow
   * module and BPMN process.
   *
   * @param call The phase-two call to dispatch
   * @param previouslyAttempted Whether the outbox entry was dispatched before (a recovered or
   *        retried entry). START operations then run the re-dispatch mitigation: they probe
   *        the workflow awareness of the recorded adapter first. A workflow which is known
   *        there already consumes the entry without a second start. Stores which cannot tell
   *        pass <code>false</code>. The mitigation is best effort, and the remaining
   *        at-least-once window is accepted.
   * @throws IllegalStateException If no process service is registered for the call's
   *         workflow module and BPMN process (for example because the BPMN process is no
   *         longer part of this application). The outbox entry stays visible in the outbox
   *         store for operations
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

    // phase two runs on the own thread of the outbox dispatcher, and calls back into the
    // application (the aggregate has to be loaded to build what the BPMS is told). Whatever
    // that needs, a transaction or an active CDI request context, VanillaBP provides here,
    // once for every outbox implementation, and not in each of them. The aggregate of the
    // entry decides which transaction it is. An application which stores this aggregate in a
    // system of its own gets its own runner here as well. An entry of an extension, which
    // routes to no process service, gets the platform's runner
    final var runner = runnerFor(call);
    metrics.outboxDispatchStarted(call.operation(), previouslyAttempted);
    // whatever the dispatch logs (and a broken BPMS connection logs a lot) names the
    // workflow it belongs to, exactly like a task delivery does
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
   * The runner which provides the transaction of a dispatch: the one which serves the
   * aggregate of the call where a process service is registered for it, otherwise the
   * platform's.
   *
   * @param call The call about to be dispatched
   * @return The runner, or <code>null</code> where neither the platform nor the aggregate's
   *         process service offers one. Then the outbox dispatches inside a transaction which
   *         it opened itself
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
   * Registers the dispatch of every operation which the core owns. Each of them routes to the
   * process service of the call's workflow module and BPMN process.
   */
  private void registerCoreOperations() {

    PhaseOperation.CORE_OPERATIONS
        .forEach(this::registerOperation);

  }

  /**
   * Registers an operation which the BPMS adapters execute, and not whoever contributed it.
   * The router resolves the process service of the call's workflow module and BPMN process,
   * converts the aggregate ID, and lets the process service run the operation's phase two
   * through the handler of the elected adapter.
   * <p>
   * The core registers its own operations this way while this router is built. An extension
   * may have an operation which addresses a workflow the way a core operation does. It says
   * so through its {@link PhaseOperation#election()}. Then it registers that operation here as
   * well, and contributes a handler per adapter, instead of registering a dispatch of its own
   * in {@link #getOperations()}.
   *
   * @param operation The operation to route to the process services
   */
  public void registerOperation(
      final PhaseOperation operation) {

    final PhaseOperationDispatch dispatch = (
        call,
        previouslyAttempted) -> dispatchToProcessService(operation, call, previouslyAttempted);
    if (PhaseOperation.coreOperation(operation.name()).isPresent()) {
      operations.registerCoreOperation(operation, dispatch);
    } else {
      operations.register(operation, dispatch);
    }

  }

  /**
   * Resolves the process service of the call's workflow module and BPMN process, converts the
   * serialized aggregate ID, and lets the service execute phase two. This is the shared
   * dispatch of every operation which an adapter executes.
   *
   * @throws IllegalStateException If no process service is registered for the call's
   *         workflow module and BPMN process
   */
  private void dispatchToProcessService(
      final PhaseOperation operation,
      final PhaseTwoCall call,
      final boolean previouslyAttempted) {

    final var processService = registrations
        .get(new RegistrationKey(call.workflowModuleId(), call.bpmnProcessId()));
    if (processService == null) {
      throw new IllegalStateException(
          """
              Cannot dispatch outbox entry (%s): BPMN process '%s' of workflow module '%s' is not \
              (or no longer) part of this application! If the process was removed on purpose, \
              remove the entry from the outbox store - it stays visible there for operations."""
              .formatted(
                  operation.describe(call.args()),
                  call.bpmnProcessId(),
                  call.workflowModuleId()));
    }

    // an operation which is not about ONE workflow has no aggregate ID. There is nothing to
    // convert, and only the routing by module and process applies
    final var workflowAggregateId = call.workflowAggregateId() == null
        ? null
        : processService.convertAggregateId(call.workflowAggregateId());

    processService
        .executePhaseTwo(operation, workflowAggregateId, call.adapterId(), call.args(), previouslyAttempted);

  }

}
