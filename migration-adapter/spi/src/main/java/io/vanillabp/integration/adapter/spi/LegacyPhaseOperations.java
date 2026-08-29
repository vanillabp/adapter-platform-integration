package io.vanillabp.integration.adapter.spi;

import java.util.Map;

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
 * <p>
 * It bridges every core operation, including the two an adapter may legitimately not be
 * able to serve: their methods answer for themselves
 * ({@link PhaseOperationNotSupported}), which is what they did before handlers existed.
 * An adapter which cannot serve them and has moved to handlers simply leaves them out of
 * its map.
 */
final class LegacyPhaseOperations {

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
                    PhaseOperationHandler.of(
                        request -> adapter
                            .startWorkflowPhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate()),
                        request -> adapter
                            .startWorkflowPhaseTwo(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregateId()))),
            Map
                .entry(
                    PhaseOperation.COMPLETE_TASK,
                    PhaseOperationHandler.of(
                        request -> adapter
                            .completeTaskPhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate(),
                                request.taskId()),
                        request -> adapter
                            .completeTaskPhaseTwo(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregateId(),
                                request.taskId()))),
            Map
                .entry(
                    PhaseOperation.CANCEL_TASK,
                    PhaseOperationHandler.of(
                        request -> adapter
                            .cancelTaskPhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate(),
                                request.taskId(),
                                request.bpmnErrorCode()),
                        request -> adapter
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
                    PhaseOperationHandler.of(
                        request -> adapter
                            .completeUserTaskPhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate(),
                                request.taskId()),
                        request -> adapter
                            .completeUserTaskPhaseTwo(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregateId(),
                                request.taskId()))),
            Map
                .entry(
                    PhaseOperation.CANCEL_USER_TASK,
                    PhaseOperationHandler.of(
                        request -> adapter
                            .cancelUserTaskPhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate(),
                                request.taskId(),
                                request.bpmnErrorCode()),
                        request -> adapter
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
                    PhaseOperationHandler.of(
                        request -> adapter
                            .correlateMessagePhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate(),
                                request.messageName(),
                                request.correlationId()),
                        request -> adapter
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
                    PhaseOperationHandler.of(
                        request -> adapter
                            .startWorkflowByMessagePhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate(),
                                request.messageName()),
                        request -> adapter
                            .startWorkflowByMessagePhaseTwo(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregateId(),
                                request.messageName()))),
            Map
                .entry(
                    PhaseOperation.SEND_SIGNAL,
                    PhaseOperationHandler.of(
                        request -> adapter
                            .sendSignalPhaseOne(
                                request.workflowModuleId(), request.bpmnProcessId(),
                                request.signalName()),
                        request -> adapter
                            .sendSignalPhaseTwo(
                                request.workflowModuleId(), request.bpmnProcessId(), request.signalName()))),
            Map
                .entry(
                    PhaseOperation.AGGREGATE_CHANGED,
                    PhaseOperationHandler.of(
                        request -> adapter
                            .aggregateChangedPhaseOne(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregate(),
                                request.taskId()),
                        request -> adapter
                            .aggregateChangedPhaseTwo(
                                request.workflowModuleId(),
                                request.bpmnProcessId(),
                                request.aggregatePersistence(),
                                request.workflowAggregateId(),
                                request.taskId()))));

  }

}
