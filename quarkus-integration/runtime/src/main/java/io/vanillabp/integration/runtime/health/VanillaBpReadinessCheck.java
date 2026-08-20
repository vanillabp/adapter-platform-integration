package io.vanillabp.integration.runtime.health;

import java.util.List;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

import io.vanillabp.integration.adapter.migration.health.AdapterHealthReport;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Publishes what VanillaBP's BPMS adapters say about their BPMS as the readiness
 * check <code>vanillabp</code>, so <code>/q/health/ready</code> shows a cluster an
 * application cannot reach.
 * <p>
 * Readiness rather than liveness on purpose: an unreachable BPMS means this instance
 * cannot do its work, not that the JVM is broken - restarting it changes nothing.
 * <p>
 * MicroProfile Health knows UP and DOWN and nothing in between, so the
 * {@link AdapterHealth.Status#UNKNOWN} of an adapter which is not configured yet is
 * reported as UP with its status in the data. The application booted with a guiding
 * warning on purpose, and an incomplete configuration must not read as an outage. An
 * adapter contributing nothing at all is absent from the data rather than reported as
 * healthy.
 * <p>
 * This class is registered as a bean ONLY if the application uses the SmallRye Health
 * extension (see {@code VanillaBpHealthBuildStepProcessor}).
 */
@Readiness
@ApplicationScoped
public class VanillaBpReadinessCheck implements HealthCheck {

  /**
   * The adapters' deployment services, one per configured adapter id. Collected the
   * way the deployment runner collects them: element beans plus the flattened
   * <code>List</code> beans of adapters with runtime-config multiplicity.
   */
  @Inject
  @Any
  Instance<AdapterDeploymentService<?, ?>> adapterDeploymentServices;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> adapterDeploymentServiceLists;

  private AdapterHealthReport report;

  @PostConstruct
  void initialize() {

    report = new AdapterHealthReport(
        () -> java.util.stream.Stream
            .concat(
                adapterDeploymentServices.stream(),
                adapterDeploymentServiceLists
                    .stream()
                    .filter(java.util.Objects::nonNull)
                    .flatMap(List::stream))
            .filter(java.util.Objects::nonNull)
            .<AdapterDeploymentService<?, ?>>map(service -> service)
            .toList());

  }

  @Override
  public HealthCheckResponse call() {

    final var healths = report.collect();
    final HealthCheckResponseBuilder response = HealthCheckResponse
        .named(AdapterHealthReport.HEALTH_NAME)
        .status(AdapterHealthReport.overallStatus(healths) != AdapterHealth.Status.DOWN);
    healths
        .forEach(health -> {
          final var prefix = health.adapterId()
              + ".";
          response.withData(prefix
              + "status",
              health
                  .status()
                  .name());
          response.withData(prefix
              + "type", health.adapterType());
          if (health.description() != null) {
            response.withData(prefix
                + "description", health.description());
          }
          health
              .details()
              .forEach((
                  name,
                  value) -> response.withData(prefix + name, value));
        });
    return response.build();

  }

}
