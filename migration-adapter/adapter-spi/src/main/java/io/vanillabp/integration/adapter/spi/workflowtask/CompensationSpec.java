package io.vanillabp.integration.adapter.spi.workflowtask;

import java.util.List;

/**
 * One compensation throw event of a BPMN process, together with the handlers it starts. A
 * BPMS adapter reports it to
 * {@link WorkflowTaskWiring#reportCompensation(String, String, java.util.Collection)} while
 * it wires a model.
 * <p>
 * A throw event which starts ONE handler is not reported. The workflow then holds one token,
 * the same as anywhere else in the model. A throw event which starts several handlers is
 * reported. From that moment the workflow holds one token per handler, and every handler
 * writes the same workflow aggregate.
 *
 * @param throwEventId The BPMN ID of the compensation throw event. That is an intermediate
 *          throw event or an end event with a compensation event definition
 * @param handlerIds The BPMN IDs of the compensation handlers it starts, at least two, in
 *          the order the model lists them. This is the order of the model. It promises
 *          nothing about the order in which an engine runs them
 */
public record CompensationSpec(
                               String throwEventId,
                               List<String> handlerIds) {
}
