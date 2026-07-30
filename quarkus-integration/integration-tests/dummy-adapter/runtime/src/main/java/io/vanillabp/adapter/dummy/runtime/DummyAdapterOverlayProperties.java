package io.vanillabp.adapter.dummy.runtime;

import java.util.Map;
import java.util.Optional;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.annotations.StaticInitSafe;
import io.smallrye.config.ConfigMapping;

/**
 * The dummy adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree - the reference implementation of the pattern every VanillaBP adapter
 * extension uses to contribute its own keys (e.g. connection settings) to the
 * canonical per-adapter location <code>vanillabp.adapters.&lt;id&gt;.*</code>:
 * <ul>
 *   <li>a second RUN_TIME {@code @ConfigMapping} over the same prefix coexists with
 *       the platform's mapping - overlapping keys are served to both;</li>
 *   <li>SmallRye's unknown-key validation passes if ANY registered mapping knows a
 *       key, so platform keys ({@code type}, {@code deployment-failure}) and
 *       adapter keys ({@code test}) validate each other's sections - no blanket
 *       {@code withMappingIgnore} needed (a typo under {@code vanillabp.*} fails
 *       the startup again);</li>
 *   <li>the adapter-id set is NEVER derived from this overlay map - it always
 *       comes from the platform's core properties
 *       ({@code MigrationAdapterProperties.adapterTypes()}), the overlay is a
 *       per-known-id lookup only.</li>
 * </ul>
 */
@StaticInitSafe
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
@ConfigMapping(prefix = "vanillabp")
public interface DummyAdapterOverlayProperties {

  /**
   * The adapter sections of the shared tree, keyed by adapter ID - only the dummy
   * adapter's own keys are modeled here.
   */
  Map<String, DummyAdapterConfig> adapters();

  /**
   * The dummy adapter's keys of one <code>vanillabp.adapters.&lt;id&gt;</code>
   * section.
   */
  interface DummyAdapterConfig {

    /**
     * A test value used by the platform integration's tests to prove that
     * adapter-specific keys inside the shared tree are tolerated and reach the
     * adapter's overlay typed.
     */
    Optional<Integer> test();

  }

}
