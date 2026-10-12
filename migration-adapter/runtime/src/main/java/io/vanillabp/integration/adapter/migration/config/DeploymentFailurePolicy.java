package io.vanillabp.integration.adapter.migration.config;

/**
 * How to treat a failed deployment of BPMS resources for a specific adapter (property
 * <code>vanillabp.adapters.&lt;id&gt;.deployment-failure</code>).
 */
public enum DeploymentFailurePolicy {

  /**
   * A failed deployment stops the boot of the application. This is the default.
   */
  FAIL,

  /**
   * A failed deployment of an adapter which is NOT first priority is logged, and the
   * application still starts. An example is the old BPMS of a migration which cannot be
   * reached for a while. A failure of the first-priority adapter always fails the boot,
   * whatever this policy says.
   */
  WARN

}
