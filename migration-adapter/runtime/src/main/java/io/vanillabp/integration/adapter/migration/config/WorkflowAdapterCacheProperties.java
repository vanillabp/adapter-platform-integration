package io.vanillabp.integration.adapter.migration.config;

import java.time.Duration;

import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Configuration of the default election cache
 * ({@link io.vanillabp.integration.spi.WorkflowAdapterCache}, properties section
 * <code>vanillabp.workflow-adapter-cache</code>) - the single source of truth for
 * keys, defaults and documentation, used by both platform integrations.
 * <p>
 * The bounds are hard on purpose: entries are hints, so losing one costs an extra
 * probing walk (and, on an eventually consistent BPMS, its visibility window) but
 * never correctness. An application with more workflows in flight
 * than the cache holds raises {@link #maxEntries} - roughly 300 bytes per entry, so
 * 100.000 entries cost about 30 MB. Why the bound is not a soft reference is
 * written down in <code>migration-adapter/README.md</code>.
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class WorkflowAdapterCacheProperties {

  public static final String SECTION = MigrationAdapterProperties.PREFIX
      + ".workflow-adapter-cache";

  public static final String MAX_ENTRIES_PROPERTY = SECTION
      + ".max-entries";

  public static final String TIME_TO_LIVE_PROPERTY = SECTION
      + ".time-to-live";

  public static final int DEFAULT_MAX_ENTRIES = 10_000;

  public static final Duration DEFAULT_TIME_TO_LIVE = Duration.ofHours(1);

  /**
   * The maximum number of entries the in-memory default cache holds - the least
   * recently used entry is dropped beyond that. Raise it if an application keeps
   * more workflows hot than the cache holds (see the eviction-pressure warning of
   * {@code WorkflowAdapterCacheStatistics}).
   */
  @Builder.Default
  private int maxEntries = DEFAULT_MAX_ENTRIES;

  /**
   * How long an entry of the in-memory default cache is kept (counted from the
   * moment it was stored). Expiry is not a defect: the hint is only a shortcut of
   * the probing walk which re-elects and re-populates it.
   */
  @Builder.Default
  private Duration timeToLive = DEFAULT_TIME_TO_LIVE;

  /**
   * Validates the bounds at startup like every other property - an unconfigured
   * application boots with the defaults.
   *
   * @throws IllegalStateException Naming the offending property, its value and the
   *           default
   */
  public void validate() {

    if (maxEntries < 1) {
      throw new IllegalStateException(
          """
              The property '%s' is %d but has to be at least 1! The election cache is bounded on \
              purpose (a full cache of the default %d entries costs about 3 MB of heap). Remove the \
              property to use the default or set the number of workflows the application keeps hot."""
              .formatted(MAX_ENTRIES_PROPERTY, maxEntries, DEFAULT_MAX_ENTRIES));
    }

    if ((timeToLive == null) || timeToLive.isZero() || timeToLive.isNegative()) {
      throw new IllegalStateException(
          """
              The property '%s' is '%s' but has to be a positive duration (e.g. 'PT1H' or '30m')! \
              An entry which expires immediately would turn every election into a full probing walk. \
              Remove the property to use the default of %s."""
              .formatted(TIME_TO_LIVE_PROPERTY, timeToLive, DEFAULT_TIME_TO_LIVE));
    }

  }

}
