/**
 * The SPI a BPMS adapter implements. Everything in this package and its sub-packages is
 * called by the VanillaBP core; business code never sees a type from here.
 * <p>
 * An adapter implements two interfaces, one instance of each per configured adapter id:
 * <ul>
 * <li>{@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService} reads the BPMN
 * files of a workflow module, rewrites them for the BPMS, deploys them and opens whatever
 * fetches work;</li>
 * <li>{@link io.vanillabp.integration.adapter.spi.MigratableProcessService} answers what the
 * BPMS is asked to DO (one {@link io.vanillabp.integration.adapter.spi.PhaseOperationHandler}
 * per {@link io.vanillabp.integration.spi.PhaseOperation}) and what it is asked ABOUT (the
 * awareness probes and the viewer methods).</li>
 * </ul>
 * Read those two type javadocs first. The second one carries the election contract and the
 * two-phase contract in full, and the rest of this package is reached from the two.
 * <p>
 * What the platform hands an adapter arrives in one
 * {@link io.vanillabp.integration.adapter.spi.AdapterCollaborators}, built per adapter id and
 * taken as a constructor argument. What an adapter calls back while it deploys is
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring} and
 * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport}; what it calls per
 * delivery is
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker}.
 * <p>
 * The narrative version of all of it, written for a team building an adapter without access
 * to this repository, is <code>ADAPTER-AUTHORS.md</code> of the
 * <code>migration-adapter</code> module: which interface to write first, what the core does
 * on an adapter's behalf so that no adapter builds it twice, what stops a boot, and the
 * checklist before a first release.
 */
package io.vanillabp.integration.adapter.spi;
