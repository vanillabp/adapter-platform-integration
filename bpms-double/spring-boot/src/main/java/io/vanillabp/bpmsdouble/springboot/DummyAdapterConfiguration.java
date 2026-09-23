package io.vanillabp.bpmsdouble.springboot;

import org.springframework.boot.autoconfigure.AutoConfiguration;

import io.vanillabp.bpmsdouble.DummyAdapter;
import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;

/**
 * Announces the BPMS double to the platform. It carries no other bean definition,
 * because it has to run before the platform's own auto-configuration.
 */
@AutoConfiguration(before = SpringBootMigrationAdapterAutoConfiguration.class)
public class DummyAdapterConfiguration extends AdapterConfigurationBase {

  /**
   * The adapter type every instance of the double reports. A test writes it into
   * <code>vanillabp.adapters.&lt;id&gt;.type</code> to configure one.
   */
  public static final String ADAPTER_TYPE = DummyAdapter.ADAPTER_TYPE;

  /**
   * Spring builds this auto-configuration once per application, before the platform's
   * own one. A test never creates it.
   */
  public DummyAdapterConfiguration() {
  }

  /**
   * @return The adapter TYPE of the double, which is not an adapter id: the same type
   *         may be configured under several ids, and that is what lets a test play a
   *         migration between two of them
   */
  @Override
  public String getAdapterType() {
    return ADAPTER_TYPE;
  }

}
