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
 */
public enum NameClashAvoidance {

  /**
   * Nothing is scoped - the application guarantees that its identifiers are unique
   * across all of its workflow modules. The least surprising choice for an
   * application consisting of exactly one workflow module.
   */
  NONE,

  /**
   * The BPMS' own isolation mechanism is used - for Camunda 7 and Camunda 8 a
   * TENANT named after the workflow module (the name is overridable by the
   * adapter's <code>tenant-id</code>). This is what VanillaBP 1 did, which is why
   * it is the default.
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
