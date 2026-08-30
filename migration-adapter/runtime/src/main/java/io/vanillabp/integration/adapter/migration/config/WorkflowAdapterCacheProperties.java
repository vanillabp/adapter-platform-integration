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

  public static final String ENDED_TIME_TO_LIVE_PROPERTY = SECTION
      + ".ended-time-to-live";

  public static final int DEFAULT_MAX_ENTRIES = 10_000;

  public static final Duration DEFAULT_TIME_TO_LIVE = Duration.ofHours(1);

  public static final Duration DEFAULT_ENDED_TIME_TO_LIVE = Duration.ofMinutes(5);

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
   * How long the entry of a workflow which ENDED is kept - much shorter than
   * {@link #timeToLive}, because such an entry cannot become useful again. It is
   * still read while it lives, which is what keeps an operation arriving after the
   * end a warned no-op instead of an exception (see
   * {@link io.vanillabp.integration.spi.WorkflowAdapterCache#putEnded}).
   * <p>
   * The value answers one question: how long after the end of a workflow may
   * something still arrive for it? Five minutes cover the message which crossed the
   * end and the outbox entry dispatched behind it; the rest is what the BPMS itself
   * remembers about instances it finished.
   */
  @Builder.Default
  private Duration endedTimeToLive = DEFAULT_ENDED_TIME_TO_LIVE;

  /**
   * Whether VanillaBP asks the BPMS to report the end of a workflow FOR THE CACHE's
   * sake, so an ended workflow lets go of its entry after {@link #endedTimeToLive}
   * instead of keeping it for a full {@link #timeToLive}. Defaults to
   * <code>false</code>, because a BPMS reports the end only where somebody asked for
   * it: switching this on attaches a listener respectively a worker to every deployed
   * process of every workflow module.
   * <p>
   * It is the third consumer of that one signal, next to an application's
   * <code>&#64;WorkflowEnded</code> method and
   * <code>vanillabp.delivery.release-on-workflow-end</code>. Where one of those asks
   * for the end anyway the notification arrives regardless of this setting, and the
   * entry is marked at no extra cost - a shared cache of a cluster is where that
   * saving is worth configuring for its own sake.
   */
  @Builder.Default
  private boolean releaseOnWorkflowEnd = false;

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

    if ((endedTimeToLive == null) || endedTimeToLive.isZero() || endedTimeToLive.isNegative()) {
      throw new IllegalStateException(
          """
              The property '%s' is '%s' but has to be a positive duration (e.g. 'PT5M' or '30s')! \
              It is how long the entry of a workflow which ended is kept, and an entry expiring \
              immediately turns an operation arriving after the end into a walk over all adapters. \
              Remove the property to use the default of %s."""
              .formatted(ENDED_TIME_TO_LIVE_PROPERTY, endedTimeToLive, DEFAULT_ENDED_TIME_TO_LIVE));
    }

    if (endedTimeToLive.compareTo(timeToLive) > 0) {
      throw new IllegalStateException(
          """
              The property '%s' is '%s' and therefore longer than '%s' ('%s')! An ended workflow \
              would then hold its entry longer than a running one, which is the opposite of what \
              the property is for. Set it below the time-to-live or remove it to use the default \
              of %s."""
              .formatted(
                  ENDED_TIME_TO_LIVE_PROPERTY,
                  endedTimeToLive,
                  TIME_TO_LIVE_PROPERTY,
                  timeToLive,
                  DEFAULT_ENDED_TIME_TO_LIVE));
    }

  }

}
