/**
 * The SPI a BPMS adapter implements. The VanillaBP core calls everything in this package and
 * its sub-packages. Business code never sees a type from here.
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
 * Read the javadoc of these two types first. The second one has the full election contract
 * and the full two-phase contract. The rest of this package is reached from these two.
 * <p>
 * Everything the platform hands an adapter comes in one
 * {@link io.vanillabp.integration.adapter.spi.AdapterCollaborators}. It is built per adapter
 * id, and the adapter takes it as a constructor argument. While it deploys, an adapter calls
 * back {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring} and
 * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidanceSupport}. Per delivery it
 * calls
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker}.
 * <p>
 * <code>ADAPTER-AUTHORS.md</code> of the <code>migration-adapter</code> module tells all of
 * this as one text. It is written for a team which builds an adapter without access to this
 * repository. It says which interface to write first, and what the core does for an adapter
 * so that no adapter builds it twice. It also says what stops a start, and it has the
 * checklist before a first release.
 */
package io.vanillabp.integration.adapter.spi;
