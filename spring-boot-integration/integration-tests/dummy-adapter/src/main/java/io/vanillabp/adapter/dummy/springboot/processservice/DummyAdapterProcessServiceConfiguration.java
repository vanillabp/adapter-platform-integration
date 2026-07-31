package io.vanillabp.adapter.dummy.springboot.processservice;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterBeanRegistrar;
import io.vanillabp.adapter.dummy.springboot.DummyAdapterOverlayProperties;

/**
 * Wires the dummy adapter's process-service and deployment-service beans: ONE
 * element bean per configured adapter id of the dummy type, registered by the
 * imported {@link DummyAdapterBeanRegistrar} (the reference implementation of the
 * per-id bean convention).
 * <p>
 * Additionally registers the adapter's OVERLAY of the shared
 * <code>vanillabp.*</code> tree ({@link DummyAdapterOverlayProperties}) - the
 * reference implementation of contributing adapter-specific keys to the canonical
 * per-adapter location <code>vanillabp.adapters.&lt;id&gt;.*</code>.
 */
@AutoConfiguration
@EnableConfigurationProperties(DummyAdapterOverlayProperties.class)
@Import(DummyAdapterBeanRegistrar.class)
public class DummyAdapterProcessServiceConfiguration {

  /**
   * The property forcing the dummy adapter to require a two-phase commit for starting
   * workflows. Used by integration tests of
   * {@link io.vanillabp.integration.spi.PhaseTwoOutbox} implementations.
   */
  public static final String PROPERTY_TWO_PHASE_COMMIT = "dummy-adapter.two-phase-commit";

}
