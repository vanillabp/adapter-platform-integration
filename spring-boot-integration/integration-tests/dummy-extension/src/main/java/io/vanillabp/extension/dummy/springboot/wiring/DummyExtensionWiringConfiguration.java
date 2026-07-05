package io.vanillabp.extension.dummy.springboot.wiring;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;

@AutoConfiguration(after = SpringBootMigrationAdapterAutoConfiguration.class)
public class DummyExtensionWiringConfiguration {

  @Bean
  public ExtensionWiringService<Object, Object> dummyExtensionWiringService(
      final MigrationAdapterProperties properties) {

    return new DummyWiringService(properties);

  }

}
