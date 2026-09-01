package io.vanillabp.integration.adapter.spi;

/**
 * How a workflow module's identifiers are kept apart from those of other workflow
 * modules. Two modules may legitimately use the same BPMN process id, message name
 * or task definition - something has to scope them, and which mechanism is right
 * depends on the BPMS and on what the operator is willing to pay for.
 * <p>
 * Configured per adapter and resolvable per workflow module and workflow
 * (<code>vanillabp.adapters.&lt;id&gt;.name-clash-avoidance</code>, respectively the
 * same key below <code>vanillabp.workflow-modules.&lt;m&gt;.adapters.&lt;id&gt;</code>
 * and <code>...workflows.&lt;w&gt;.adapters.&lt;id&gt;</code>).
 * <p>
 * <b>The mode is not a runtime switch.</b> Changing it changes the identifiers a
 * BPMS sees, so workflows started under the old mode would no longer be found.
 * Switching is a BPMS MIGRATION: configure a second adapter id which differs only
 * in this setting and put it first in
 * <code>vanillabp.prioritized-adapters</code> - existing workflows keep running in
 * the old adapter id, new ones start in the new one.
 * <p>
 * <b>Two ids on ONE backend is what that costs.</b> The election then has to tell
 * two adapters apart which see the same tasks and workflows, so the adapter needs a
 * way to answer for its own scope only (see the election contract of
 * {@link MigratableProcessService}). Whether it has one is the adapter's answer, and
 * it may end the boot instead: Camunda 8 requires the cluster's query API for it,
 * and two Camunda 7 ids are two embedded engines, which need a
 * <code>table-prefix</code> or a <code>data-source-name</code> of their own no
 * matter which modes they use. The adapter's documentation says what applies.
 * <p>
 * Why the plain identifiers stay in the registries and only the call into the BPMS carries the
 * scoped ones is decision 9 in the repository's DECISIONS.md.
 */
public enum NameClashAvoidance {

  /**
   * Nothing is scoped - the application guarantees that its identifiers are unique
   * across all of its workflow modules. The least surprising choice for an
   * application consisting of exactly one workflow module, and what an application on
   * a BPMS which cannot isolate says: a Camunda 8 cluster without multi-tenancy
   * rejects a tenant id, and this mode (version 1's <code>use-tenants: false</code>)
   * is one of the two answers its startup message names. It is never a default,
   * because a mode nobody chose must not be the one which scopes nothing.
   * <p>
   * Since this mode protects nothing, every adapter reports it at startup per
   * workflow module and names its own alternatives
   * ({@link AdapterDeploymentService#warnAboutUnscopedIdentifiers(String, boolean)}).
   */
  NONE,

  /**
   * The BPMS' own isolation mechanism is used - for Camunda 7 and Camunda 8 a
   * TENANT named after the workflow module (the name is overridable by the
   * adapter's <code>tenant-id</code>). This is what VanillaBP 1 did, which is why it
   * is the default of every adapter
   * ({@link AdapterDeploymentService#defaultNameClashAvoidance()}): an application
   * upgrading without touching its configuration finds its running workflows again.
   * Each adapter holds that in its own test
   * ({@code Camunda7DeploymentServiceTest}, {@code Camunda8DeploymentServiceTest}).
   * <p>
   * An adapter whose BPMS has no such mechanism rejects this mode at startup with a
   * guiding message instead of silently deploying everything into one scope.
   */
  BY_ADAPTER,

  /**
   * VanillaBP prefixes the identifiers with the workflow module id (task
   * definitions additionally with the BPMN process id) before they reach the BPMS,
   * and strips the prefix off again on the way back. No tenant is involved - which
   * is the point: BPMS vendors license per tenant.
   * <p>
   * Prefixing is TRANSPARENT: business code, {@code ProcessService} calls, BPMN
   * files and configuration all use the plain identifiers. Only the BPMS sees the
   * prefixed ones.
   */
  USE_PREFIX

}
