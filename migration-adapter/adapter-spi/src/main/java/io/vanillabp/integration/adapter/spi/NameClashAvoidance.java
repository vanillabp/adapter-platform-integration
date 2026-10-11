package io.vanillabp.integration.adapter.spi;

/**
 * How the identifiers of a workflow module are kept apart from those of other workflow
 * modules. Two modules may use the same BPMN process id, message name or task
 * definition, and that is allowed. So something has to scope them. Which way is right
 * depends on the BPMS and on what the operator is willing to pay for.
 * <p>
 * It is configured per adapter, and it can be set per workflow module and per workflow
 * (<code>vanillabp.adapters.&lt;id&gt;.name-clash-avoidance</code>, or the same key
 * below <code>vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;</code>
 * and <code>...workflows.&lt;w&gt;.adapters.&lt;id&gt;</code>).
 * <p>
 * <b>The mode is not a runtime switch.</b> Changing it changes the identifiers a
 * BPMS sees, so workflows started under the old mode would no longer be found.
 * Switching is a BPMS MIGRATION. Configure a second adapter id which differs only
 * in this setting, and put it first in <code>vanillabp.prioritized-adapters</code>.
 * Existing workflows keep running in the old adapter id, new ones start in the new
 * one.
 * <p>
 * <b>The price is two ids on ONE backend.</b> The election then has to tell apart two
 * adapters which see the same tasks and workflows. So the adapter needs a way to
 * answer for its own scope only (see the election contract of
 * {@link MigratableProcessService}). Whether it has one is up to the adapter, and it
 * may stop the start instead. Camunda 8 needs the query API of the cluster for it.
 * Two Camunda 7 ids are two embedded engines, which need a <code>table-prefix</code>
 * or a <code>data-source-name</code> of their own, whatever modes they use. The
 * documentation of the adapter says what applies.
 * <p>
 * Why the plain identifiers stay in the registries and only the call into the BPMS carries the
 * scoped ones is decision 9 in the repository's DECISIONS.md.
 */
public enum NameClashAvoidance {

  /**
   * Nothing is scoped. The application guarantees that its identifiers are unique
   * across all of its workflow modules. This is the least surprising choice for an
   * application with exactly one workflow module. It is also the choice on a BPMS
   * which cannot keep modules apart. A Camunda 8 cluster without multi-tenancy rejects
   * a tenant id, and this mode (<code>use-tenants: false</code> in version 1) is one of
   * the two answers its startup message names. It is never a default, because a mode
   * nobody chose must not be the one which scopes nothing.
   * <p>
   * This mode protects nothing. So every adapter reports it at startup per workflow
   * module and names its own alternatives
   * ({@link AdapterDeploymentService#warnAboutUnscopedIdentifiers(String, boolean)}).
   */
  NONE,

  /**
   * The BPMS keeps the modules apart with its own mechanism. For Camunda 7 and
   * Camunda 8 this is a TENANT named after the workflow module (the adapter's
   * <code>tenant-id</code> can override the name). VanillaBP 1 did this, so it is the
   * default of every adapter ({@link AdapterDeploymentService#defaultNameClashAvoidance()}).
   * An application which upgrades without touching its configuration finds its running
   * workflows again. Each adapter checks that in its own test
   * ({@code Camunda7DeploymentServiceTest}, {@code Camunda8DeploymentServiceTest}).
   * <p>
   * If the BPMS of an adapter has no such mechanism, the adapter rejects this mode at
   * startup with a guiding message. It does not silently deploy everything into one
   * scope.
   */
  BY_ADAPTER,

  /**
   * VanillaBP puts the workflow module id in front of the identifiers before they
   * reach the BPMS (task definitions also get the BPMN process id). On the way back it
   * removes the prefix again. No tenant is involved, and that is the point: BPMS
   * vendors charge licences per tenant.
   * <p>
   * Prefixing is TRANSPARENT: business code, {@code ProcessService} calls, BPMN
   * files and configuration all use the plain identifiers. Only the BPMS sees the
   * prefixed ones.
   */
  USE_PREFIX

}
