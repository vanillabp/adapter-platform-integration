package io.vanillabp.integration.adapter.migration.health;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.health.AdapterHealth;
import lombok.extern.slf4j.Slf4j;

/**
 * Asks every BPMS adapter of the application what it can say about its BPMS and
 * collects the answers. One class for both platforms: Spring Boot turns the report
 * into an Actuator health indicator, Quarkus into a readiness check, and neither
 * knows anything about a BPMS.
 * <p>
 * What the report does beyond calling the adapters:
 * <ul>
 * <li>an adapter contributing <code>null</code> is left out entirely - it is not
 * reported as healthy, because nobody checked it;</li>
 * <li>an adapter throwing is reported as {@link AdapterHealth.Status#DOWN} naming the
 * exception. A health endpoint has to answer even when an adapter misbehaves, and an
 * adapter which cannot answer its own question is a defect worth seeing;</li>
 * <li>the overall status is the worst one reported, where
 * {@link AdapterHealth.Status#UNKNOWN} is NOT worse than
 * {@link AdapterHealth.Status#UP}: an application whose second adapter is not
 * configured yet is not an outage.</li>
 * </ul>
 */
@Slf4j
public class AdapterHealthReport {

  private final Supplier<Collection<AdapterDeploymentService<?, ?>>> adapters;

  /**
   * @param adapters Reports the adapter deployment services of the application, one
   *          per configured adapter id (resolved on every call - the platform's bean
   *          provider does the collecting)
   */
  public AdapterHealthReport(
      final Supplier<Collection<AdapterDeploymentService<?, ?>>> adapters) {

    this.adapters = adapters;

  }

  /**
   * Asks every adapter and returns what they answered, in the order the platform
   * reports the adapters.
   *
   * @return One entry per adapter which contributed something
   */
  public List<AdapterHealth> collect() {

    final var collected = new ArrayList<AdapterHealth>();
    for (final var adapter : adapters.get()) {
      final AdapterHealth health;
      try {
        health = adapter.checkHealth();
      } catch (final RuntimeException e) {
        log.debug("Adapter '{}' failed to report its health", adapter.getAdapterId(), e);
        collected.add(
            AdapterHealth.down(
                adapter.getAdapterId(),
                adapter.getAdapterType(),
                "The adapter failed to report its health: %s: %s".formatted(
                    e
                        .getClass()
                        .getSimpleName(),
                    e.getMessage()),
                AdapterHealth
                    .detailsBuilder()
                    .build()));
        continue;
      }
      if (health != null) {
        collected.add(health);
      }
    }
    return List.copyOf(collected);

  }

  /**
   * The status of the application as a whole: {@link AdapterHealth.Status#DOWN} if
   * any adapter is down, {@link AdapterHealth.Status#UP} if at least one adapter
   * answered and none is down, {@link AdapterHealth.Status#UNKNOWN} where nothing was
   * checked at all.
   *
   * @param healths What the adapters answered
   * @return The overall status
   */
  public static AdapterHealth.Status overallStatus(
      final List<AdapterHealth> healths) {

    if (healths
        .stream()
        .anyMatch(health -> health.status() == AdapterHealth.Status.DOWN)) {
      return AdapterHealth.Status.DOWN;
    }
    return healths
        .stream()
        .anyMatch(health -> health.status() == AdapterHealth.Status.UP)
            ? AdapterHealth.Status.UP
            : AdapterHealth.Status.UNKNOWN;

  }

  /**
   * The name a platform's health endpoint publishes this contribution under.
   */
  public static final String HEALTH_NAME = "vanillabp";

}
