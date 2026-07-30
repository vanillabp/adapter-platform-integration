package io.vanillabp.adapter.dummy.springboot.processservice;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterOverlayProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;

/**
 * Provides the dummy adapter's {@link MigratableProcessService} bean picked up by the
 * {@link io.vanillabp.spi.process.ProcessService} beans built by the VanillaBP Spring
 * Boot integration.
 * <p>
 * The bean must not have eager dependencies since it is created very early during
 * bootstrapping of the Spring context (before configuration properties beans are
 * bound). Therefore only an {@link ObjectProvider} is passed.
 * <p>
 * Additionally registers the adapter's OVERLAY of the shared
 * <code>vanillabp.*</code> tree ({@link DummyAdapterOverlayProperties}) - the
 * reference implementation of contributing adapter-specific keys to the canonical
 * per-adapter location <code>vanillabp.adapters.&lt;id&gt;.*</code>.
 */
@AutoConfiguration
@EnableConfigurationProperties(DummyAdapterOverlayProperties.class)
public class DummyAdapterProcessServiceConfiguration {

  /**
   * The property forcing the dummy adapter to require a two-phase commit for starting
   * workflows. Used by integration tests of
   * {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox} implementations.
   */
  public static final String PROPERTY_TWO_PHASE_COMMIT = "dummy-adapter.two-phase-commit";

  @Bean
  public MigratableProcessService<?> dummyMigratableProcessService(
      final ObjectProvider<MigrationAdapterProperties> properties,
      final Environment environment,
      final ObjectProvider<DummyAdapterPhaseTwoListener> phaseTwoListeners) {

    return new MigratableProcessService<>(
        properties, Boolean.TRUE.equals(
            environment.getProperty(PROPERTY_TWO_PHASE_COMMIT, Boolean.class, Boolean.FALSE)), phaseTwoListeners);

  }

}
