package io.vanillabp.integration.adapter.migration.config;

import java.time.Duration;

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
public class MetricsProperties {

  /**
   * The section an application writes these keys below: <code>vanillabp.metrics</code>.
   * Built from the prefix rather than written out, so a message names the section the way
   * the application has to spell it.
   */
  public static final String SECTION = MigrationAdapterProperties.PREFIX
      + ".metrics";

  /**
   * The key of {@link #gaugeCache}: <code>vanillabp.metrics.gauge-cache</code>. It is a
   * constant because two messages name it - the startup message about what still keeps a
   * database awake and the one {@link #validate()} throws - and a test reads the key from
   * here instead of writing it a third time.
   */
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
  private Duration gaugeCache = DEFAULT_GAUGE_CACHE;

  /**
   * The empty section a configuration binder starts from: both platforms create the object
   * and then write the keys the application configured into it, one setter per key.
   * <p>
   * It asks the builder for the values, and that is not a detour: every default of this
   * class stands on the builder as well as on the field, and both ways into an object
   * end here. A constructor which set the fields itself would have to repeat every
   * default, and the first one somebody forgets would leave an application which
   * configures no metrics without the default.
   */
  public MetricsProperties() {

    this(builder());

  }

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

  /**
   * The builder of {@link MetricsProperties}. Its two type parameters carry the class
   * being built and the builder itself, so a call inherited from a base class comes back
   * as the builder of the subclass and the next call in the chain sees every key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class MetricsPropertiesBuilder<C extends MetricsProperties, B extends MetricsProperties.MetricsPropertiesBuilder<C, B>> {

    /**
     * How long the measurement of a gauge which has to ask somebody is reused before it
     * is taken again. The builder starts from the same value the field does.
     */
    private Duration gaugeCache = DEFAULT_GAUGE_CACHE;

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one:
     * {@link MetricsProperties#builder()} hands out the builder of this class.
     */
    public MetricsPropertiesBuilder() {
    }

    /**
     * How long the measurement of a gauge which has to ask somebody is reused before it
     * is taken again.
     *
     * @param gaugeCache The value of {@link #gaugeCache}
     * @return This builder, so the calls chain
     */
    public B gaugeCache(
        final Duration gaugeCache) {

      this.gaugeCache = gaugeCache;
      return self();

    }

    /**
     * The builder itself, typed as the builder of the subclass. Every method of the
     * chain returns it, which is what keeps a chain started on a subclass builder at
     * that subclass.
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

      return "MetricsProperties.MetricsPropertiesBuilder("
          + "gaugeCache="
          + gaugeCache
          + ")";

    }

  }

  /**
   * The builder {@link #builder()} hands out: the one which builds
   * {@link MetricsProperties} itself rather than a subclass of it.
   */
  private static final class MetricsPropertiesBuilderImpl extends MetricsProperties.MetricsPropertiesBuilder<MetricsProperties, MetricsProperties.MetricsPropertiesBuilderImpl> {

    /**
     * Nobody but {@link MetricsProperties#builder()} builds one.
     */
    private MetricsPropertiesBuilderImpl() {
    }

    /**
     * This builder, typed as itself.
     *
     * @return This builder
     */
    @Override
    protected MetricsProperties.MetricsPropertiesBuilderImpl self() {

      return this;

    }

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public MetricsProperties build() {

      return new MetricsProperties(this);

    }

  }

  /**
   * What every builder of this class and of its subclasses builds through. It is the one
   * place the values of this class move from the builder into the object, so a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected MetricsProperties(
      final MetricsProperties.MetricsPropertiesBuilder<?, ?> b) {

    this.gaugeCache = b.gaugeCache;

  }

  /**
   * A builder of {@link MetricsProperties}, empty except for the values which have a
   * default.
   *
   * @return The builder
   */
  public static MetricsProperties.MetricsPropertiesBuilder<?, ?> builder() {

    return new MetricsProperties.MetricsPropertiesBuilderImpl();

  }

  /**
   * How long the measurement of a gauge which has to ask somebody is reused before it is
   * taken again.
   *
   * @return The value of {@link #gaugeCache}
   */
  public Duration getGaugeCache() {

    return gaugeCache;

  }

  /**
   * How long the measurement of a gauge which has to ask somebody is reused before it is
   * taken again.
   *
   * @param gaugeCache The value of {@link #gaugeCache}
   */
  public void setGaugeCache(
      final Duration gaugeCache) {

    this.gaugeCache = gaugeCache;

  }

}
