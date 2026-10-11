package io.vanillabp.integration.health;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import io.vanillabp.integration.adapter.migration.health.AdapterHealthReport;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;

/**
 * Publishes what VanillaBP's BPMS adapters say about their BPMS, under the health
 * component <code>vanillabp</code>. So <code>/actuator/health</code> shows a cluster
 * which an application cannot reach.
 * <p>
 * The shape of the output follows from two rules of {@link AdapterHealth}. First, the
 * adapters are listed by their id, each with what it found and the details it supplied.
 * The most important detail is the address, so the endpoint alone tells an operator which
 * system is meant. Second, an adapter which is not configured yet reports
 * {@link Status#UNKNOWN}, and Spring Boot answers that with HTTP 200. The application
 * booted with a guiding warning on purpose, and an incomplete configuration must not look
 * like an outage.
 * <p>
 * The indicator only exists where the application brings Spring Boot's health support.
 * An adapter which reports nothing is missing from the output. It is not reported as
 * healthy.
 */
public class VanillaBpHealthIndicator implements HealthIndicator {

  private final AdapterHealthReport report;

  /**
   * Built by the bean method of
   * {@link io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration}
   * which only runs where Spring Boot's health support is on the classpath. Its bean name
   * decides the name of the health component, so it is fixed there and not here.
   *
   * @param report Asks the adapters on every call, so an adapter which is created late is
   *     asked as well. This indicator keeps nothing of its own
   */
  public VanillaBpHealthIndicator(
      final AdapterHealthReport report) {

    this.report = report;

  }

  @Override
  public Health health() {

    final var healths = report.collect();
    final var builder = new Health.Builder(statusOf(AdapterHealthReport.overallStatus(healths)));
    healths
        .forEach(health -> builder
            .withDetail(
                health.adapterId(),
                detailsOf(health)));
    return builder.build();

  }

  private static Map<String, Object> detailsOf(
      final AdapterHealth health) {

    final var detail = new LinkedHashMap<String, Object>();
    detail.put("status", statusOf(health.status()).getCode());
    detail.put("type", health.adapterType());
    if (health.description() != null) {
      detail.put("description", health.description());
    }
    detail.putAll(health.details());
    return detail;

  }

  private static Status statusOf(
      final AdapterHealth.Status status) {

    return switch (status) {
      case UP -> Status.UP;
      case DOWN -> Status.DOWN;
      case UNKNOWN -> Status.UNKNOWN;
    };

  }

}
