package io.vanillabp.integration.adapter.spi.workflowtask;

import java.util.List;

/**
 * One compensation throw event of a BPMN process together with the handlers it starts,
 * reported by a BPMS adapter to
 * {@link WorkflowTaskWiring#reportCompensation(String, String, java.util.Collection)} while
 * it wires a model.
 * <p>
 * A throw event which starts ONE handler is not reported: the workflow holds one token then,
 * the same as anywhere else in the model. Reported is a throw event which starts several,
 * because from that moment the workflow holds a token per handler and every one of them
 * writes the same workflow aggregate.
 *
 * @param throwEventId The BPMN ID of the compensation throw event - an intermediate throw
 *          event or an end event carrying a compensation event definition
 * @param handlerIds The BPMN IDs of the compensation handlers it starts, at least two, in
 *          the order the model lists them. The order is the model's, not a promise about
 *          the order an engine runs them in
 */
public record CompensationSpec(
                               String throwEventId,
                               List<String> handlerIds) {
}
