package io.vanillabp.adapter.dummy.springboot.processservice;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;

/**
 * Provides the dummy adapter's {@link MigratableProcessService} bean picked up by the
 * {@link io.vanillabp.spi.process.ProcessService} beans built by the VanillaBP Spring
 * Boot integration.
 * <p>
 * The bean must not have eager dependencies since it is created very early during
 * bootstrapping of the Spring context (before configuration properties beans are
 * bound). Therefore only an {@link ObjectProvider} is passed.
 */
@AutoConfiguration
public class DummyAdapterProcessServiceConfiguration {

  @Bean
  public MigratableProcessService<?> dummyMigratableProcessService(
      final ObjectProvider<MigrationAdapterProperties> properties) {

    return new MigratableProcessService<>(properties);

  }

}
