package io.vanillabp.loadtest.quarkus;

import java.util.List;
import java.util.function.Supplier;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.agroal.api.AgroalDataSource;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.loadharness.ConnectionPoolProbe;
import io.vanillabp.loadharness.LoadAggregate;
import io.vanillabp.loadharness.LoadApplication;
import io.vanillabp.loadharness.LoadDriver;
import io.vanillabp.loadharness.LoadProcess;
import io.vanillabp.spi.process.ProcessService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The beans of the load run on Quarkus: the hooks of the double, the aggregates, and the few
 * lines which reach the platform.
 */
@ApplicationScoped
public class LoadBeans {

  /**
   * The id of the double, as <code>application.yaml</code> configures it.
   */
  static final String ADAPTER_ID = "load";

  @Produces
  @Singleton
  LoadDriver loadDriver(
      final QuarkusLoadAggregates aggregates) {

    return new LoadDriver(aggregates);

  }

  @Produces
  @Singleton
  LoadProcess loadProcess(
      @ConfigProperty(name = "load.service-tasks", defaultValue = "3") final int serviceTasks) {

    return new LoadProcess(serviceTasks);

  }

  /**
   * Quarkus adds every registry bean to its composite registry, and only a composite with a
   * child records anything.
   */
  @Produces
  @Singleton
  SimpleMeterRegistry loadMeterRegistry() {

    return new SimpleMeterRegistry();

  }

  @Produces
  @Singleton
  LoadApplication loadApplication(
      final ProcessService<LoadAggregate> processService,
      @Any final Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices,
      final MeterRegistry meters,
      final AgroalDataSource dataSource) {

    final var pool = new ConnectionPoolProbe() {

      @Override
      public int active() {

        return (int) dataSource.getMetrics().activeCount();

      }

      @Override
      public int pending() {

        return (int) dataSource.getMetrics().awaitingCount();

      }

    };
    return new LoadApplication() {

      @Override
      public <T> T inTransaction(
          final Supplier<T> work) {

        return QuarkusTransaction.requiringNew().call(work::get);

      }

      @Override
      public ProcessService<LoadAggregate> processService() {

        return processService;

      }

      @Override
      public DummyDeploymentService bpms() {

        return deploymentServices
            .stream()
            .filter(java.util.Objects::nonNull)
            .flatMap(List::stream)
            .filter(DummyDeploymentService.class::isInstance)
            .map(DummyDeploymentService.class::cast)
            .filter(service -> ADAPTER_ID.equals(service.getAdapterId()))
            .findFirst()
            .orElseThrow();

      }

      @Override
      public String workflowModuleId() {

        return "load";

      }

      @Override
      public MeterRegistry meters() {

        return meters;

      }

      @Override
      public ConnectionPoolProbe connectionPool() {

        return pool;

      }

    };

  }

}
