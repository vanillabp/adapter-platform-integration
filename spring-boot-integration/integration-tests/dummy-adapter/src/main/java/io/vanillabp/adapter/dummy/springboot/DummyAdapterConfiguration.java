package io.vanillabp.adapter.dummy.springboot;

import org.springframework.boot.autoconfigure.AutoConfiguration;

import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;

/**
 * Provides a dummy adapter used by integration tests.
 * <p>
 * This configuration should not contain any other bean definitions
 * since this configuration needs to be run very early.
 */
@AutoConfiguration(before = SpringBootMigrationAdapterAutoConfiguration.class)
public class DummyAdapterConfiguration extends AdapterConfigurationBase {

  public static final String ADAPTER_TYPE = "dummy";

  /**
   * @return The ID of the adapter
   */
  @Override
  public String getAdapterType() {
    return ADAPTER_TYPE;
  }

}
