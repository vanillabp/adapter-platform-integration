package io.vanillabp.integration.adapter.spi;

import java.util.List;
import java.util.Map;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;

/**
 * The bridge which lets an adapter written against the pair of methods per operation
 * keep working now that an adapter contributes a handler per operation instead. It is
 * what {@link MigratableProcessService#phaseOperations()} answers by default, so an
 * adapter which changes nothing behaves exactly as before, and an adapter which moves
 * one operation at a time merges its own handlers into this map.
 * <p>
 * This class is temporary by design. It disappears together with the methods it calls,
 * once the adapters which VanillaBP ships have moved, and it is the ONE place which
 * knows both shapes - adding an operation does not touch it, because a new operation
 * has no pair of methods to bridge to.
 */
final class LegacyPhaseOperations {

  /**
   * A handler built from the pair of methods rather than contributed by the adapter.
   * The core recognizes it ({@link #isBridge(PhaseOperationHandler)}) so it can ask
   * the second question a bridge leaves open: whether the adapter overrides the
   * methods behind it at all.
   */
  private record Bridge<A>(
                           java.util.function.Consumer<PhaseOneRequest<A>> one,
                           java.util.function.Consumer<PhaseTwoRequest<A>> two) implements PhaseOperationHandler<A> {

    @Override
    public void phaseOne(
        final PhaseOneRequest<A> request) {

      one.accept(request);

    }

    @Override
    public void phaseTwo(
        final PhaseTwoRequest<A> request) {

      two.accept(request);

    }

  }

  /**
   * The pair of methods an operation had before it became a handler, by operation
   * name. Both names are looked up with the parameter types the interface declares
   * them with, erased - which is what an implementing class either overrides or
   * inherits.
   */
  private record LegacyMethods(
                               String phaseOne,
                               String phaseTwo,
                               Class<?>[] phaseOneParameters,
                               Class<?>[] phaseTwoParameters) {
  }

  private static final Class<?>[] START = {
      String.class, String.class, AggregatePersistenceAware.class, Object.class
  };

  private static final Class<?>[] TASK = {
      String.class, String.class, AggregatePersistenceAware.class, Object.class, String.class
  };

  private static final Class<?>[] TASK_WITH_ERROR = {
      String.class, String.class, AggregatePersistenceAware.class, Object.class, String.class, String.class
  };

  private static final Class<?>[] SIGNAL = {
      String.class, String.class, String.class
  };

  private static final Map<String, LegacyMethods> LEGACY_METHODS = Map
      .ofEntries(
          Map
              .entry(
                  PhaseOperation.START_WORKFLOW.name(),
                  new LegacyMethods("startWorkflowPhaseOne", "startWorkflowPhaseTwo", START, START)),
          Map
              .entry(
                  PhaseOperation.COMPLETE_TASK.name(),
                  new LegacyMethods("completeTaskPhaseOne", "completeTaskPhaseTwo", TASK, TASK)),
          Map
              .entry(
                  PhaseOperation.CANCEL_TASK.name(),
                  new LegacyMethods(
                      "cancelTaskPhaseOne", "cancelTaskPhaseTwo", TASK_WITH_ERROR, TASK_WITH_ERROR)),
          Map
              .entry(
                  PhaseOperation.COMPLETE_USER_TASK.name(),
                  new LegacyMethods("completeUserTaskPhaseOne", "completeUserTaskPhaseTwo", TASK, TASK)),
          Map
              .entry(
                  PhaseOperation.CANCEL_USER_TASK.name(),
                  new LegacyMethods(
                      "cancelUserTaskPhaseOne", "cancelUserTaskPhaseTwo", TASK_WITH_ERROR, TASK_WITH_ERROR)),
          Map
              .entry(
                  PhaseOperation.CORRELATE_MESSAGE.name(),
                  new LegacyMethods(
                      "correlateMessagePhaseOne", "correlateMessagePhaseTwo", TASK_WITH_ERROR, TASK_WITH_ERROR)),
          Map
              .entry(
                  PhaseOperation.START_WORKFLOW_BY_MESSAGE.name(),
                  new LegacyMethods(
                      "startWorkflowByMessagePhaseOne", "startWorkflowByMessagePhaseTwo", TASK, TASK)),
          Map
              .entry(
                  PhaseOperation.SEND_SIGNAL.name(),
                  new LegacyMethods("sendSignalPhaseOne", "sendSignalPhaseTwo", SIGNAL, SIGNAL)),
          Map
              .entry(
                  PhaseOperation.AGGREGATE_CHANGED.name(),
                  new LegacyMethods("aggregateChangedPhaseOne", "aggregateChangedPhaseTwo", TASK, TASK)));

  private LegacyPhaseOperations() {
  }

  /**
   * @param <A> The workflow-aggregate type
   * @param adapter The adapter whose pair of methods the handlers call
   * @return A handler per core operation, each calling the methods the operation used
   *         to be
   */
  static <A> Map<PhaseOperation, PhaseOperationHandler<A>> of(
      final MigratableProcessService<A> adapter) {

    return Map
        .ofEntries(
            Map
                .entry(
                    PhaseOperation.START_WORKFLOW,
                    new Bridge<A>(
                        request -> adapter
                            .startWorkflowPhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate()), request -> adapter
                                    .startWorkflowPhaseTwo(
                                        request.workflowModuleId(),
                                        request.bpmnProcessId(),
                                        request.aggregatePersistence(),
                                        request.workflowAggregateId()))),
            Map
                .entry(
                    PhaseOperation.COMPLETE_TASK,
                    new Bridge<A>(
                        request -> adapter
                            .completeTaskPhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate(),
                                request.taskId()), request -> adapter
                                    .completeTaskPhaseTwo(
                                        request.workflowModuleId(),
                                        request.bpmnProcessId(),
                                        request.aggregatePersistence(),
                                        request.workflowAggregateId(),
                                        request.taskId()))),
            Map
                .entry(
                    PhaseOperation.CANCEL_TASK,
                    new Bridge<A>(
                        request -> adapter
                            .cancelTaskPhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate(),
                                request.taskId(),
                                request.bpmnErrorCode()), request -> adapter
                                    .cancelTaskPhaseTwo(
                                        request.workflowModuleId(),
                                        request.bpmnProcessId(),
                                        request.aggregatePersistence(),
                                        request.workflowAggregateId(),
                                        request.taskId(),
                                        request.bpmnErrorCode()))),
            Map
                .entry(
                    PhaseOperation.COMPLETE_USER_TASK,
                    new Bridge<A>(
                        request -> adapter
                            .completeUserTaskPhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate(),
                                request.taskId()), request -> adapter
                                    .completeUserTaskPhaseTwo(
                                        request.workflowModuleId(),
                                        request.bpmnProcessId(),
                                        request.aggregatePersistence(),
                                        request.workflowAggregateId(),
                                        request.taskId()))),
            Map
                .entry(
                    PhaseOperation.CANCEL_USER_TASK,
                    new Bridge<A>(
                        request -> adapter
                            .cancelUserTaskPhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate(),
                                request.taskId(),
                                request.bpmnErrorCode()), request -> adapter
                                    .cancelUserTaskPhaseTwo(
                                        request.workflowModuleId(),
                                        request.bpmnProcessId(),
                                        request.aggregatePersistence(),
                                        request.workflowAggregateId(),
                                        request.taskId(),
                                        request.bpmnErrorCode()))),
            Map
                .entry(
                    PhaseOperation.CORRELATE_MESSAGE,
                    new Bridge<A>(
                        request -> adapter
                            .correlateMessagePhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate(),
                                request.messageName(),
                                request.correlationId()), request -> adapter
                                    .correlateMessagePhaseTwo(
                                        request.workflowModuleId(),
                                        request.bpmnProcessId(),
                                        request.aggregatePersistence(),
                                        request.workflowAggregateId(),
                                        request.messageName(),
                                        request.correlationId(),
                                        request.activationId()))),
            Map
                .entry(
                    PhaseOperation.START_WORKFLOW_BY_MESSAGE,
                    new Bridge<A>(
                        request -> adapter
                            .startWorkflowByMessagePhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate(),
                                request.messageName()), request -> adapter
                                    .startWorkflowByMessagePhaseTwo(
                                        request.workflowModuleId(),
                                        request.bpmnProcessId(),
                                        request.aggregatePersistence(),
                                        request.workflowAggregateId(),
                                        request.messageName()))),
            Map
                .entry(
                    PhaseOperation.SEND_SIGNAL,
                    new Bridge<A>(
                        request -> adapter
                            .sendSignalPhaseOne(
                                request.workflowModuleId(), request.bpmnProcessId(),
                                request.signalName()), request -> adapter
                                    .sendSignalPhaseTwo(
                                        request.workflowModuleId(), request.bpmnProcessId(), request.signalName()))),
            Map
                .entry(
                    PhaseOperation.AGGREGATE_CHANGED,
                    new Bridge<A>(
                        request -> adapter
                            .aggregateChangedPhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate(),
                                request.taskId()), request -> adapter
                                    .aggregateChangedPhaseTwo(
                                        request.workflowModuleId(),
                                        request.bpmnProcessId(),
                                        request.aggregatePersistence(),
                                        request.workflowAggregateId(),
                                        request.taskId()))));

  }

  /**
   * @param handler A handler an adapter answered with
   * @return Whether it is one of the bridges above rather than one the adapter wrote
   */
  static boolean isBridge(
      final PhaseOperationHandler<?> handler) {

    return handler instanceof Bridge;

  }

  /**
   * Whether the adapter overrides BOTH methods a bridge calls. Asked about an adapter
   * which has not moved to handlers yet: without an override the bridge would call the
   * interface's own method, which throws where the operation is required and reports
   * "this BPMS has nothing like it" where it is not. Neither is an answer somebody
   * wants to read from a workflow standing still, so the boot asks first.
   *
   * @param adapterClass The adapter's class
   * @param operation The operation to ask about
   * @return Whether the pair of methods is implemented
   */
  static boolean isImplementedBy(
      final Class<?> adapterClass,
      final PhaseOperation operation) {

    final var methods = LEGACY_METHODS.get(operation.name());
    if (methods == null) {
      // an operation which never had a pair of methods: only a handler can serve it
      return false;
    }
    return isOverridden(adapterClass, methods.phaseOne(), methods.phaseOneParameters()) && isOverridden(adapterClass,
        methods.phaseTwo(), methods.phaseTwoParameters());

  }

  /**
   * The names of the two methods an operation used to be, for a message which tells an
   * adapter author what to implement.
   *
   * @param operation The operation to ask about
   * @return The two method names, empty for an operation which never had them
   */
  static List<String> legacyMethodNames(
      final PhaseOperation operation) {

    final var methods = LEGACY_METHODS.get(operation.name());
    return methods == null
        ? List.of()
        : List.of(methods.phaseOne(), methods.phaseTwo());

  }

  private static boolean isOverridden(
      final Class<?> adapterClass,
      final String name,
      final Class<?>[] parameterTypes) {

    try {
      // resolves to the override where there is one, and to the interface's own method
      // otherwise - which is exactly the question asked here
      return adapterClass.getMethod(name, parameterTypes).getDeclaringClass() != MigratableProcessService.class;
    } catch (final NoSuchMethodException e) {
      // an adapter compiled against an SPI which did not have the method
      return false;
    }

  }

}
