package io.vanillabp.integration.adapter.migration.config;

import java.time.Duration;

/**
 * The configuration of the default election cache
 * ({@link io.vanillabp.integration.spi.WorkflowAdapterCache}, properties section
 * <code>vanillabp.workflow-adapter-cache</code>). It is the one source of truth for keys,
 * defaults and documentation, and both platform integrations use it.
 * <p>
 * The bounds are hard on purpose: entries are hints, so losing one costs an extra
 * probing walk (and, on an eventually consistent BPMS, its visibility window) but
 * never correctness. An application with more workflows in flight
 * than the cache holds raises {@link #maxEntries} - roughly 300 bytes per entry, so
 * 100.000 entries cost about 30 MB. Why the bound is not a soft reference is
 * written down in <code>migration-adapter/README.md</code>.
 */
public class WorkflowAdapterCacheProperties {

  /**
   * The section below which an application writes these keys:
   * <code>vanillabp.workflow-adapter-cache</code>. It is built from the prefix and not
   * written out. So a message names the section the way the application has to spell it.
   */
  public static final String SECTION = MigrationAdapterProperties.PREFIX
      + ".workflow-adapter-cache";

  /**
   * The key of {@link #maxEntries}:
   * <code>vanillabp.workflow-adapter-cache.max-entries</code>. The message which
   * {@link #validate()} throws names it, and so does the warning about a cache under eviction
   * pressure. So both say what the reader has to write.
   */
  public static final String MAX_ENTRIES_PROPERTY = SECTION
      + ".max-entries";

  /**
   * The key of {@link #timeToLive}:
   * <code>vanillabp.workflow-adapter-cache.time-to-live</code>.
   */
  public static final String TIME_TO_LIVE_PROPERTY = SECTION
      + ".time-to-live";

  /**
   * The key of {@link #endedTimeToLive}:
   * <code>vanillabp.workflow-adapter-cache.ended-time-to-live</code>.
   */
  public static final String ENDED_TIME_TO_LIVE_PROPERTY = SECTION
      + ".ended-time-to-live";

  /**
   * The default of {@link #maxEntries}: ten thousand workflows kept hot. That is about 3 MB
   * of heap, at roughly 300 bytes per entry.
   */
  public static final int DEFAULT_MAX_ENTRIES = 10_000;

  /**
   * The default of {@link #timeToLive}: one hour.
   */
  public static final Duration DEFAULT_TIME_TO_LIVE = Duration.ofHours(1);

  /**
   * The default of {@link #endedTimeToLive}: five minutes. In that window an operation which
   * arrives after the end of its workflow is still answered from the cache.
   */
  public static final Duration DEFAULT_ENDED_TIME_TO_LIVE = Duration.ofMinutes(5);

  /**
   * The maximum number of entries which the in-memory default cache holds. Beyond that, the
   * least recently used entry is dropped. Raise it if an application keeps more workflows hot
   * than the cache holds (see the warning about eviction pressure of
   * {@code WorkflowAdapterCacheStatistics}).
   * <p>
   * Key {@value #MAX_ENTRIES_PROPERTY}, {@value #DEFAULT_MAX_ENTRIES} by default.
   */
  private int maxEntries = DEFAULT_MAX_ENTRIES;

  /**
   * How long an entry of the in-memory default cache is kept, counted from the moment it was
   * stored. Expiry is not a defect. The hint is only a shortcut of the probing walk, which
   * elects again and fills the cache again.
   * <p>
   * Key {@value #TIME_TO_LIVE_PROPERTY}, one hour by default.
   */
  private Duration timeToLive = DEFAULT_TIME_TO_LIVE;

  /**
   * How long the entry of a workflow which ENDED is kept. It is much shorter than
   * {@link #timeToLive}, because such an entry cannot become useful again. It is still read
   * while it lives. That keeps an operation which arrives after the end a warned no-op, and
   * not an exception (see
   * {@link io.vanillabp.integration.spi.WorkflowAdapterCache#putEnded}).
   * <p>
   * Key {@value #ENDED_TIME_TO_LIVE_PROPERTY}, five minutes by default.
   * <p>
   * The value answers one question: how long after the end of a workflow may
   * something still arrive for it? Five minutes cover the message which crossed the
   * end and the outbox entry dispatched behind it; the rest is what the BPMS itself
   * remembers about instances it finished.
   */
  private Duration endedTimeToLive = DEFAULT_ENDED_TIME_TO_LIVE;

  /**
   * Whether VanillaBP asks the BPMS to report the end of a workflow FOR THE CACHE's sake.
   * Then an ended workflow lets go of its entry after {@link #endedTimeToLive}, instead of
   * keeping it for a full {@link #timeToLive}. The default is <code>false</code>, because a
   * BPMS only reports the end where somebody asked for it. Switching this on attaches a
   * listener or a worker to every deployed process of every workflow module.
   * <p>
   * It is the third consumer of that one signal, next to an application's
   * <code>&#64;WorkflowEnded</code> method and
   * <code>vanillabp.delivery.release-on-workflow-end</code>. Where one of those asks
   * for the end anyway the notification arrives regardless of this setting, and the
   * entry is marked at no extra cost - a shared cache of a cluster is where that
   * saving is worth configuring for its own sake.
   * <p>
   * Key <code>vanillabp.workflow-adapter-cache.release-on-workflow-end</code>,
   * <code>false</code> by default.
   */
  private boolean releaseOnWorkflowEnd = false;

  /**
   * The empty section from which a configuration binder starts. Both platforms create the
   * object, and then write the keys which the application configured into it, one setter per
   * key.
   * <p>
   * It asks the builder for the values, and that is not a detour. Every default of this class
   * is on the builder as well as on the field, and both ways into an object end here. A
   * constructor which set the fields itself would have to repeat every default. The first
   * default somebody forgets would leave an application which configures no cache with a
   * bound of zero entries and no time to live at all.
   */
  public WorkflowAdapterCacheProperties() {

    this(builder());

  }

  /**
   * Checks the limits at startup, like every other property. An application which configures
   * nothing boots with the defaults.
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

  /**
   * The builder of {@link WorkflowAdapterCacheProperties}. Its two type parameters are the
   * class being built and the builder itself. So a call inherited from a base class comes
   * back as the builder of the subclass, and the next call in the chain sees every key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class WorkflowAdapterCachePropertiesBuilder<C extends WorkflowAdapterCacheProperties, B extends WorkflowAdapterCacheProperties.WorkflowAdapterCachePropertiesBuilder<C, B>> {

    /**
     * The maximum number of entries which the in-memory default cache holds. Beyond that, the
     * least recently used entry is dropped. The builder starts from the same value as the
     * field.
     */
    private int maxEntries = DEFAULT_MAX_ENTRIES;

    /**
     * How long an entry of the in-memory default cache is kept, counted from the moment it
     * was stored. The builder starts from the same value as the field.
     */
    private Duration timeToLive = DEFAULT_TIME_TO_LIVE;

    /**
     * How long the entry of a workflow which ENDED is kept. It is much shorter than
     * {@link #timeToLive}, because such an entry cannot become useful again. The builder
     * starts from the same value as the field.
     */
    private Duration endedTimeToLive = DEFAULT_ENDED_TIME_TO_LIVE;

    /**
     * Whether VanillaBP asks the BPMS to report the end of a workflow FOR THE CACHE's sake.
     * Then an ended workflow lets go of its entry after {@link #endedTimeToLive}, instead of
     * keeping it for a full {@link #timeToLive}. The builder starts from the same value as
     * the field.
     */
    private boolean releaseOnWorkflowEnd = false;

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one.
     * {@link WorkflowAdapterCacheProperties#builder()} hands out the builder of this class.
     */
    public WorkflowAdapterCachePropertiesBuilder() {
    }

    /**
     * The maximum number of entries which the in-memory default cache holds. Beyond that, the
     * least recently used entry is dropped.
     *
     * @param maxEntries The value of {@link #maxEntries}
     * @return This builder, so the calls chain
     */
    public B maxEntries(
        final int maxEntries) {

      this.maxEntries = maxEntries;
      return self();

    }

    /**
     * How long an entry of the in-memory default cache is kept, counted from the moment it
     * was stored.
     *
     * @param timeToLive The value of {@link #timeToLive}
     * @return This builder, so the calls chain
     */
    public B timeToLive(
        final Duration timeToLive) {

      this.timeToLive = timeToLive;
      return self();

    }

    /**
     * How long the entry of a workflow which ENDED is kept. It is much shorter than
     * {@link #timeToLive}, because such an entry cannot become useful again.
     *
     * @param endedTimeToLive The value of {@link #endedTimeToLive}
     * @return This builder, so the calls chain
     */
    public B endedTimeToLive(
        final Duration endedTimeToLive) {

      this.endedTimeToLive = endedTimeToLive;
      return self();

    }

    /**
     * Whether VanillaBP asks the BPMS to report the end of a workflow FOR THE CACHE's sake.
     * Then an ended workflow lets go of its entry after {@link #endedTimeToLive}, instead of
     * keeping it for a full {@link #timeToLive}.
     *
     * @param releaseOnWorkflowEnd The value of {@link #releaseOnWorkflowEnd}
     * @return This builder, so the calls chain
     */
    public B releaseOnWorkflowEnd(
        final boolean releaseOnWorkflowEnd) {

      this.releaseOnWorkflowEnd = releaseOnWorkflowEnd;
      return self();

    }

    /**
     * The builder itself, typed as the builder of the subclass. Every method of the chain
     * returns it. That keeps a chain which started on a subclass builder at that subclass.
     *
     * @return This builder
     */
    protected abstract B self();

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    public abstract C build();

    /**
     * What this builder holds, for a message and for a debugger.
     *
     * @return The name of this builder and every value written into it
     */
    @Override
    public String toString() {

      return "WorkflowAdapterCacheProperties.WorkflowAdapterCachePropertiesBuilder("
          + "maxEntries="
          + maxEntries
          + ", "
          + "timeToLive="
          + timeToLive
          + ", "
          + "endedTimeToLive="
          + endedTimeToLive
          + ", "
          + "releaseOnWorkflowEnd="
          + releaseOnWorkflowEnd
          + ")";

    }

  }

  /**
   * The builder which {@link #builder()} hands out: the one which builds
   * {@link WorkflowAdapterCacheProperties} itself, and not a subclass of it.
   */
  private static final class WorkflowAdapterCachePropertiesBuilderImpl extends WorkflowAdapterCacheProperties.WorkflowAdapterCachePropertiesBuilder<WorkflowAdapterCacheProperties, WorkflowAdapterCacheProperties.WorkflowAdapterCachePropertiesBuilderImpl> {

    /**
     * Nobody but {@link WorkflowAdapterCacheProperties#builder()} builds one.
     */
    private WorkflowAdapterCachePropertiesBuilderImpl() {
    }

    /**
     * This builder, typed as itself.
     *
     * @return This builder
     */
    @Override
    protected WorkflowAdapterCacheProperties.WorkflowAdapterCachePropertiesBuilderImpl self() {

      return this;

    }

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public WorkflowAdapterCacheProperties build() {

      return new WorkflowAdapterCacheProperties(this);

    }

  }

  /**
   * Every builder of this class and of its subclasses builds through this. It is the one
   * place where the values of this class move from the builder into the object. So a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected WorkflowAdapterCacheProperties(
      final WorkflowAdapterCacheProperties.WorkflowAdapterCachePropertiesBuilder<?, ?> b) {

    this.maxEntries = b.maxEntries;
    this.timeToLive = b.timeToLive;
    this.endedTimeToLive = b.endedTimeToLive;
    this.releaseOnWorkflowEnd = b.releaseOnWorkflowEnd;

  }

  /**
   * A builder of {@link WorkflowAdapterCacheProperties}. It is empty, except for the values
   * which have a default.
   *
   * @return The builder
   */
  public static WorkflowAdapterCacheProperties.WorkflowAdapterCachePropertiesBuilder<?, ?> builder() {

    return new WorkflowAdapterCacheProperties.WorkflowAdapterCachePropertiesBuilderImpl();

  }

  /**
   * The maximum number of entries which the in-memory default cache holds. Beyond that, the
   * least recently used entry is dropped.
   *
   * @return The value of {@link #maxEntries}
   */
  public int getMaxEntries() {

    return maxEntries;

  }

  /**
   * How long an entry of the in-memory default cache is kept, counted from the moment it was
   * stored.
   *
   * @return The value of {@link #timeToLive}
   */
  public Duration getTimeToLive() {

    return timeToLive;

  }

  /**
   * How long the entry of a workflow which ENDED is kept. It is much shorter than
   * {@link #timeToLive}, because such an entry cannot become useful again.
   *
   * @return The value of {@link #endedTimeToLive}
   */
  public Duration getEndedTimeToLive() {

    return endedTimeToLive;

  }

  /**
   * Whether VanillaBP asks the BPMS to report the end of a workflow FOR THE CACHE's sake.
   * Then an ended workflow lets go of its entry after {@link #endedTimeToLive}, instead of
   * keeping it for a full {@link #timeToLive}.
   *
   * @return The value of {@link #releaseOnWorkflowEnd}
   */
  public boolean isReleaseOnWorkflowEnd() {

    return releaseOnWorkflowEnd;

  }

  /**
   * The maximum number of entries which the in-memory default cache holds. Beyond that, the
   * least recently used entry is dropped.
   *
   * @param maxEntries The value of {@link #maxEntries}
   */
  public void setMaxEntries(
      final int maxEntries) {

    this.maxEntries = maxEntries;

  }

  /**
   * How long an entry of the in-memory default cache is kept, counted from the moment it was
   * stored.
   *
   * @param timeToLive The value of {@link #timeToLive}
   */
  public void setTimeToLive(
      final Duration timeToLive) {

    this.timeToLive = timeToLive;

  }

  /**
   * How long the entry of a workflow which ENDED is kept. It is much shorter than
   * {@link #timeToLive}, because such an entry cannot become useful again.
   *
   * @param endedTimeToLive The value of {@link #endedTimeToLive}
   */
  public void setEndedTimeToLive(
      final Duration endedTimeToLive) {

    this.endedTimeToLive = endedTimeToLive;

  }

  /**
   * Whether VanillaBP asks the BPMS to report the end of a workflow FOR THE CACHE's sake.
   * Then an ended workflow lets go of its entry after {@link #endedTimeToLive}, instead of
   * keeping it for a full {@link #timeToLive}.
   *
   * @param releaseOnWorkflowEnd The value of {@link #releaseOnWorkflowEnd}
   */
  public void setReleaseOnWorkflowEnd(
      final boolean releaseOnWorkflowEnd) {

    this.releaseOnWorkflowEnd = releaseOnWorkflowEnd;

  }

}
