package io.vanillabp.migration.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.spi.PhaseOperation;

/**
 * The phase operations of an adapter double which writes down what it was asked to do.
 * <p>
 * A test which used to verify that the core reached one method of the adapter asks this
 * instead: the map an adapter answers IS what the core calls, so what a test wants to
 * know is which handler ran with which arguments.
 *
 * @param <A> The workflow-aggregate type
 */
public final class RecordedPhaseOperations<A> {

  /**
   * One call of one phase.
   *
   * @param operation The operation which ran
   * @param workflowModuleId The workflow module it belonged to
   * @param bpmnProcessId The BPMN process it belonged to
   * @param aggregatePersistence The persistence it was handed
   * @param workflowAggregate The aggregate (phase one) respectively its ID (phase two)
   * @param args The operation's arguments
   */
  public record Call(
                     PhaseOperation operation,
                     String workflowModuleId,
                     String bpmnProcessId,
                     Object aggregatePersistence,
                     Object workflowAggregate,
                     Map<String, String> args) {
  }

  private final List<Call> phaseOne = new ArrayList<>();

  private final List<Call> phaseTwo = new ArrayList<>();

  /**
   * @return A handler per core operation, each of them recording what it was asked
   */
  public Map<PhaseOperation, PhaseOperationHandler<A>> operations() {

    final var operations = new HashMap<PhaseOperation, PhaseOperationHandler<A>>();
    PhaseOperation.CORE_OPERATIONS
        .forEach(
            operation -> operations
                .put(
                    operation,
                    PhaseOperationHandler
                        .of(
                            request -> phaseOne
                                .add(
                                    new Call(
                                        operation, request.workflowModuleId(), request.bpmnProcessId(), request
                                            .aggregatePersistence(), request.workflowAggregate(), request.args())),
                            request -> phaseTwo
                                .add(
                                    new Call(
                                        operation, request.workflowModuleId(), request.bpmnProcessId(), request
                                            .aggregatePersistence(), request.workflowAggregateId(), request.args())))));
    return operations;

  }

  /**
   * @return What phase one was asked to do, in order
   */
  public List<Call> phaseOne() {

    return phaseOne;

  }

  /**
   * @return What phase two was asked to do, in order
   */
  public List<Call> phaseTwo() {

    return phaseTwo;

  }

  /**
   * @param operation The operation to look for
   * @return The calls of phase one of that operation
   */
  public List<Call> phaseOneOf(
      final PhaseOperation operation) {

    return phaseOne
        .stream()
        .filter(call -> call.operation().equals(operation))
        .toList();

  }

  /**
   * @param operation The operation to look for
   * @return The calls of phase two of that operation
   */
  public List<Call> phaseTwoOf(
      final PhaseOperation operation) {

    return phaseTwo
        .stream()
        .filter(call -> call.operation().equals(operation))
        .toList();

  }

}
