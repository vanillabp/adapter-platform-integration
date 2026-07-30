package io.vanillabp.adapter.dummy.springboot.deployment;

import java.util.Map;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;

/**
 * Registers the dummy adapter's deployment service as an <i>element</i> bean - never
 * as a bean of type <code>List&lt;AdapterDeploymentService&gt;</code>: the platform
 * collects all adapters' deployment services via <code>ObjectProvider</code> streams,
 * and only element beans allow several adapter types to coexist in one application
 * (the central migration scenario; a List bean per adapter breaks collection
 * injection as soon as a second adapter is present).
 * <p>
 * Currently ONE instance is built for the first configured adapter id of the dummy
 * type - per-adapter-id multiplicity (one element bean per configured id) is
 * introduced by the adapter-config-model story (26d).
 */
@AutoConfiguration(after = SpringBootMigrationAdapterAutoConfiguration.class)
public class DummyAdapterDeploymentConfiguration {

  @Bean
  public DeploymentService dummyDeploymentService(
      final MigrationAdapterProperties properties) {

    final var adapterId = properties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> adapter.getValue().equals(DummyAdapterConfiguration.ADAPTER_TYPE))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse("");

    return new DeploymentService(adapterId);

  }

}
