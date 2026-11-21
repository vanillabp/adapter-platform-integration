package io.vanillabp.integration.adapter.spi.workflowtask;

import io.vanillabp.integration.spi.workflowtask.MultiInstanceIteration;

/**
 * The context of one multi-instance execution a task runs in. The BPMS adapter supplies it
 * through {@link TaskInvocationContext#getMultiInstances()}. An extension reads it as
 * {@link MultiInstanceIteration} of the integration SPI and never names this record.
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
                                 int total) implements MultiInstanceIteration {
}
