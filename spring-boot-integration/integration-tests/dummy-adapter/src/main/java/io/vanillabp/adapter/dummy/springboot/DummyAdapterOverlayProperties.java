package io.vanillabp.adapter.dummy.springboot;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import lombok.Getter;
import lombok.Setter;

/**
 * The dummy adapter's OVERLAY of the shared <code>vanillabp.*</code> configuration
 * tree - the reference implementation of the pattern every VanillaBP adapter uses on
 * Spring Boot to contribute its own keys (e.g. connection settings) to the canonical
 * per-adapter location <code>vanillabp.adapters.&lt;id&gt;.*</code>: a second
 * {@code @ConfigurationProperties("vanillabp")} class coexists with the platform's
 * binding of the core model (same-prefix classes bind side by side; keys unknown to
 * either view are ignored by the JavaBean binding).
 * <p>
 * The adapter-id set is NEVER derived from this overlay map - it always comes from
 * the platform's core properties ({@code MigrationAdapterProperties.adapterTypes()});
 * the overlay is a per-known-id lookup only (environment-variable overrides can
 * materialize phantom map entries in the overlay).
 */
@ConfigurationProperties(MigrationAdapterProperties.PREFIX)
@Getter
@Setter
public class DummyAdapterOverlayProperties {

  /**
   * The adapter sections of the shared tree, keyed by adapter ID - only the dummy
   * adapter's own keys are modeled here.
   */
  private Map<String, DummyAdapterConfig> adapters = Map.of();

  /**
   * The dummy adapter's keys of one <code>vanillabp.adapters.&lt;id&gt;</code>
   * section.
   */
  @Getter
  @Setter
  public static class DummyAdapterConfig {

    /**
     * A test value used by the platform integration's tests to prove that
     * adapter-specific keys inside the shared tree are tolerated and reach the
     * adapter's overlay typed.
     */
    private Integer test;

  }

}
