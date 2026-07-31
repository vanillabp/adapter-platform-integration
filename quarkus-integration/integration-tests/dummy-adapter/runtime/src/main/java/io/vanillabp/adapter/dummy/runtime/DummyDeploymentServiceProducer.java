package io.vanillabp.adapter.dummy.runtime;

import java.util.List;
import java.util.Map;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Provides the dummy adapter's {@link DummyDeploymentService} instances - the
 * reference implementation of the per-adapter-id bean convention every VanillaBP
 * adapter follows on Quarkus (same shape as for the process services, see
 * {@link DummyProcessServiceProducer}): ONE bean of type
 * <code>List&lt;AdapterDeploymentService&lt;Object, Object&gt;&gt;</code> with one
 * instance PER configured adapter id of its type.
 * <p>
 * Conventions (part of the platform contract):
 * <ul>
 *   <li>The List's element type is the SPI interface with BOTH type parameters
 *       literally {@code Object} - regardless of the adapter's actual model and
 *       context classes (unchecked cast if needed): CDI's parameterized-type
 *       matching of differing type arguments is not reliable across modes, so the
 *       platform looks the beans up with the exact type. The pipeline matches
 *       models via {@code getModelType()}/{@code getProcessContextType()}, never
 *       via the generics.</li>
 *   <li>The producer method is {@code @Singleton}: deployment-service
 *       implementations usually have no no-arg constructor and are therefore not
 *       client-proxyable - a normal-scoped <i>element</i> bean of the
 *       implementation class would fail the deployment.</li>
 *   <li>The adapter-id set ALWAYS comes from the platform's core properties
 *       ({@code adapterTypes()}).</li>
 * </ul>
 */
@ApplicationScoped
public class DummyDeploymentServiceProducer {

  @Produces
  @Singleton
  public List<AdapterDeploymentService<Object, Object>> dummyAdapterDeploymentServices(
      final MigrationAdapterProperties properties,
      @Any final Instance<DummyDeploymentListener> deploymentListeners) {

    return properties
        .adapterTypes()
        .entrySet()
        .stream()
        .filter(adapter -> DummyProcessServiceProducer.ADAPTER_TYPE.equals(adapter.getValue()))
        .map(Map.Entry::getKey)
        .sorted()
        .<AdapterDeploymentService<Object, Object>>map(
            adapterId -> new DummyDeploymentService(adapterId, deploymentListeners))
        .toList();

  }

}
