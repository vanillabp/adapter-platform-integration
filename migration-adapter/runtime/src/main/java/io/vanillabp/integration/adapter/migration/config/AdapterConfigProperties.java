package io.vanillabp.integration.adapter.migration.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * The configuration of one adapter instance
 * (properties section <code>vanillabp.adapters.&lt;id&gt;.*</code>).
 * <p>
 * The properties modeled here are the platform-neutral ones owned by the
 * migration adapter. BPMS adapters contribute their own keys to the same
 * properties section (e.g. connection settings) by binding an overlay view of
 * the <code>vanillabp.*</code> tree - those keys are not modeled here.
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class AdapterConfigProperties {

  /**
   * The adapter's type in case of a custom adapter identifier or null in case
   * of a non-custom adapter identifier (the adapter's ID is the type then -
   * see {@link MigrationAdapterProperties#adapterTypes()}).
   */
  private String type;

  /**
   * How to treat a failing deployment of BPMS resources for this adapter:
   * {@link DeploymentFailurePolicy#FAIL} (default) aborts booting of the
   * application; {@link DeploymentFailurePolicy#WARN} logs the failure of a
   * NON-first-priority adapter and the application still starts (a failure of
   * the first-priority adapter always fails the boot).
   */
  private DeploymentFailurePolicy deploymentFailure;

  /**
   * Convenience factory for an adapter configuration of the given type.
   *
   * @param type The adapter's type
   * @return The adapter configuration
   */
  public static AdapterConfigProperties ofType(
      final String type) {

    return AdapterConfigProperties
        .builder()
        .type(type)
        .build();

  }

}
