package io.vanillabp.integration.adapter.spi.workflowtask;

/**
 * The context of one multi-instance execution a task runs in, supplied by the BPMS
 * adapter via {@link TaskInvocationContext#getMultiInstances()}.
 *
 * @param element The current element of the multi-instance iteration (bound to
 *          <code>&#64;MultiInstanceElement</code> parameters)
 * @param index The current index of the multi-instance iteration (bound to
 *          <code>&#64;MultiInstanceIndex</code> parameters)
 * @param total The total number of iterations (bound to
 *          <code>&#64;MultiInstanceTotal</code> parameters)
 */
public record MultiInstanceValue(
                                 Object element,
                                 int index,
                                 int total) {
}
