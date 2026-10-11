package io.vanillabp.integration.test.adapter;

import java.util.List;
import java.util.Map;

import io.quarkus.arc.Unremovable;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Produces one {@link TestAdapterDeploymentService} for each configured adapter id, of ANY
 * type. This is a shortcut for the test. Real adapters filter by their type. The runtime
 * deployment pipeline needs one deployment service for each prioritized adapter id, like
 * on Spring Boot.
 */
@ApplicationScoped
public class TestAdapterDeploymentServiceProducer {

  @Produces
  @Singleton
  @Unremovable
  public List<AdapterDeploymentService<Object, Object>> testAdapterDeploymentServices(
      final MigrationAdapterProperties properties) {

    return properties
        .adapterTypes()
        .entrySet()
        .stream()
        .sorted(Map.Entry.comparingByKey())
        .<AdapterDeploymentService<Object, Object>>map(
            adapter -> new TestAdapterDeploymentService(adapter.getKey(), adapter.getValue()))
        .toList();

  }

}
