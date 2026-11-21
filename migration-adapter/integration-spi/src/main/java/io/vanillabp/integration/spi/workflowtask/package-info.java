/**
 * What the core and the BPMS adapters say about the workflow services and the tasks of the
 * application, for BPMS adapters and extensions alike.
 * <ul>
 * <li>{@link io.vanillabp.integration.spi.workflowtask.WorkflowTaskClaims} answers whether a
 * <code>&#64;WorkflowService</code> class claims a BPMN process, and the name of the ID
 * property of its workflow aggregate. The core implements it, and both platform
 * integrations publish it as a bean. A BPMS adapter gets the same methods through
 * <code>WorkflowTaskWiring</code> of the adapter SPI, which extends it.</li>
 * <li>{@link io.vanillabp.integration.spi.workflowtask.MultiInstanceIteration} and
 * {@link io.vanillabp.integration.spi.workflowtask.BpmnTaskElement} are how an extension
 * reads what a BPMS adapter answers about a task: one iteration of a multi-instance element,
 * and the names of a task element. The records of the adapter SPI implement them.</li>
 * </ul>
 * An extension takes these interfaces and never the types of the adapter SPI behind them.
 * They live here and not in the extension SPI, because the adapter SPI needs them too, and
 * both SPIs depend on this module.
 */
package io.vanillabp.integration.spi.workflowtask;
