package io.vanillabp.integration.adapter.migration.config;

import java.time.Duration;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Configuration of what VanillaBP publishes as metrics (properties section
 * <code>vanillabp.metrics</code>). There is one setting, and it exists because of one
 * rule: reading a metric must not cost anything worth noticing.
 * <p>
 * Most of what VanillaBP reports is a number it already has - a counter, a timer, the
 * size of a map. A few gauges have to ask somebody: how many entries wait in the
 * phase-two outbox is a query against the outbox store. A gauge is read on every
 * collection, Prometheus collects every fifteen seconds by default, a dashboard asks in
 * between and every instance of the application answers for itself, so a gauge which
 * queries would turn watching the system into load on it. {@link #gaugeCache} is how
 * long one such measurement is reused instead.
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class MetricsProperties {

  public static final String SECTION = MigrationAdapterProperties.PREFIX
      + ".metrics";

  public static final String GAUGE_CACHE_PROPERTY = SECTION
      + ".gauge-cache";

  /**
   * The default of {@link #gaugeCache} in ISO-8601 notation, for javadoc and messages.
   */
  public static final String DEFAULT_GAUGE_CACHE_ISO = "PT10S";

  /**
   * The default of {@link #gaugeCache}: ten seconds.
   */
  public static final Duration DEFAULT_GAUGE_CACHE = Duration.parse(DEFAULT_GAUGE_CACHE_ISO);

  /**
   * How long the measurement of a gauge which has to ask somebody is reused before it
   * is taken again. Default: {@value #DEFAULT_GAUGE_CACHE_ISO}.
   * <p>
   * <b>Why ten seconds.</b> It is one collection interval, a little under the fifteen
   * seconds Prometheus scrapes with by default. Every scrape therefore gets a
   * measurement of its own, while the dashboard somebody opens next to it, a second
   * collector, and a liveness check asking at the same moment all read the number the
   * scrape already paid for. Raise it where the query is heavier than a counting one,
   * lower it where a backlog has to be visible faster than the scrape interval.
   * <p>
   * <code>PT0S</code> switches the holding off, so every collection measures. That is
   * what a test wants when it has just changed something and needs to see it; in an
   * application it means paying for the query as often as somebody looks.
   */
  @Builder.Default
  private Duration gaugeCache = DEFAULT_GAUGE_CACHE;

  /**
   * The duration to hold a measurement for, with the default applied where nothing is
   * configured.
   *
   * @return The duration, never <code>null</code>
   */
  public Duration resolvedGaugeCache() {

    return gaugeCache == null
        ? DEFAULT_GAUGE_CACHE
        : gaugeCache;

  }

  /**
   * Validates the duration at startup like every other property - an unconfigured
   * application boots with the default.
   *
   * @throws IllegalStateException Naming the offending property, its value and the
   *           default
   */
  public void validate() {

    if ((gaugeCache == null) || !gaugeCache.isNegative()) {
      return;
    }
    throw new IllegalStateException(
        """
            The property '%s' is '%s' but has to be a duration of zero or more! It is how long the \
            measurement of a gauge which has to ask somebody - the number of waiting outbox entries, \
            for instance - is reused before it is taken again, and a negative span is neither a \
            duration nor a way to switch anything off. Remove the property to use the default of %s, \
            or set 'PT0S' to measure on every collection."""
            .formatted(GAUGE_CACHE_PROPERTY, gaugeCache, DEFAULT_GAUGE_CACHE_ISO));

  }

}
