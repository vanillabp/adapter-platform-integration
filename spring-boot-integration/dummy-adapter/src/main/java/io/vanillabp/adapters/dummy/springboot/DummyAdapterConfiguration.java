package io.vanillabp.adapters.dummy.springboot;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;

/**
 * Provides a dummy adapter used by integration tests.
 */
@Configuration
@AutoConfiguration(before = SpringBootMigrationAdapterAutoConfiguration.class)
public class DummyAdapterConfiguration extends AdapterConfigurationBase {

  /**
   * @return The ID of the adapter
   */
  @Override
  public String getAdapterType() {
    return "dummy";
  }

}
