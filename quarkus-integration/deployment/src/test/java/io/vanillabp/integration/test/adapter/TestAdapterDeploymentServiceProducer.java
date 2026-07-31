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
 * Produces one {@link TestAdapterDeploymentService} per configured adapter id (of
 * ANY type - a test fixture shortcut; real adapters filter by their type): the
 * runtime deployment pipeline requires one deployment service per prioritized
 * adapter id, like on Spring Boot.
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
