package io.vanillabp.integration.adapter.migration.config;

/**
 * How to treat a failing deployment of BPMS resources for a specific adapter
 * (property <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code>).
 */
public enum DeploymentFailurePolicy {

  /**
   * A failing deployment aborts booting of the application. This is the default.
   */
  FAIL,

  /**
   * A failing deployment of a NON-first-priority adapter (e.g. the old BPMS during a
   * migration being temporarily unreachable) is logged and the application still
   * starts. A failure of the first-priority adapter always fails the boot,
   * regardless of this policy.
   */
  WARN

}
