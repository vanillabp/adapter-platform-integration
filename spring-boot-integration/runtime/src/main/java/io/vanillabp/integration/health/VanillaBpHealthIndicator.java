package io.vanillabp.integration.health;

import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

import io.vanillabp.integration.adapter.migration.health.AdapterHealthReport;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;

/**
 * Publishes what VanillaBP's BPMS adapters say about their BPMS under the health
 * component <code>vanillabp</code>, so <code>/actuator/health</code> shows a cluster
 * an application cannot reach.
 * <p>
 * The shape of the output follows from two rules of {@link AdapterHealth}. Adapters
 * are listed by their id, each with what it found and the details it supplied - the
 * address above all, so the endpoint alone tells an operator which system is meant.
 * And an adapter which is not configured yet contributes {@link Status#UNKNOWN},
 * which Spring Boot answers with HTTP 200: the application booted with a guiding
 * warning on purpose, and an incomplete configuration must not read as an outage.
 * <p>
 * The indicator exists only where the application brings Spring Boot's health
 * support; an adapter contributing nothing is absent from the output rather than
 * reported as healthy.
 */
public class VanillaBpHealthIndicator implements HealthIndicator {

  private final AdapterHealthReport report;

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

  private static java.util.Map<String, Object> detailsOf(
      final AdapterHealth health) {

    final var detail = new java.util.LinkedHashMap<String, Object>();
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
