package io.vanillabp.integration.adapter.migration.config;

import java.time.Duration;

/**
 * The configuration of what VanillaBP publishes as metrics (properties section
 * <code>vanillabp.metrics</code>). There is one setting, and it exists because of one rule:
 * reading a metric must not cost anything worth noticing.
 * <p>
 * Most of what VanillaBP reports is a number which it already has: a counter, a timer, the
 * size of a map. A few gauges have to ask somebody. How many entries wait in the phase-two
 * outbox is a query to the outbox store. A gauge is read on every collection. Prometheus
 * collects every fifteen seconds by default, a dashboard asks in between, and every instance
 * of the application answers for itself. So a gauge which queries would turn watching the
 * system into load on it. {@link #gaugeCache} says how long one such measurement is reused
 * instead.
 */
public class MetricsProperties {

  /**
   * The section below which an application writes these keys: <code>vanillabp.metrics</code>.
   * It is built from the prefix and not written out. So a message names the section the way
   * the application has to spell it.
   */
  public static final String SECTION = MigrationAdapterProperties.PREFIX
      + ".metrics";

  /**
   * The key of {@link #gaugeCache}: <code>vanillabp.metrics.gauge-cache</code>. It is a
   * constant, because two messages name it: the startup message about what still keeps a
   * database awake, and the one which {@link #validate()} throws. And a test reads the key
   * from here, instead of writing it a third time.
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
   * How long the measurement of a gauge which has to ask somebody is reused, before it is
   * taken again. Default: {@value #DEFAULT_GAUGE_CACHE_ISO}.
   * <p>
   * <b>Why ten seconds.</b> It is one collection interval, a little under the fifteen seconds
   * at which Prometheus scrapes by default. So every scrape gets a measurement of its own. A
   * dashboard which somebody opens next to it, a second collector, and a liveness check which
   * asks at the same moment all read the number which the scrape already paid for. Raise it
   * where the query is heavier than a count. Lower it where a backlog has to be visible
   * faster than the scrape interval.
   * <p>
   * <code>PT0S</code> switches the holding off, so every collection measures. A test wants
   * that when it has just changed something and needs to see it. In an application it means
   * paying for the query as often as somebody looks.
   */
  private Duration gaugeCache = DEFAULT_GAUGE_CACHE;

  /**
   * The empty section from which a configuration binder starts. Both platforms create the
   * object, and then write the keys which the application configured into it, one setter per
   * key.
   * <p>
   * It asks the builder for the values, and that is not a detour. Every default of this class
   * is on the builder as well as on the field, and both ways into an object end here. A
   * constructor which set the fields itself would have to repeat every default. The first
   * default somebody forgets would leave an application which configures no metrics without
   * the default.
   */
  public MetricsProperties() {

    this(builder());

  }

  /**
   * How long a measurement is held, with the default where nothing is configured.
   *
   * @return The duration, never <code>null</code>
   */
  public Duration resolvedGaugeCache() {

    return gaugeCache == null
        ? DEFAULT_GAUGE_CACHE
        : gaugeCache;

  }

  /**
   * Checks the duration at startup, like every other property. An application which
   * configures nothing boots with the default.
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
   * The builder of {@link MetricsProperties}. Its two type parameters are the class being
   * built and the builder itself. So a call inherited from a base class comes back as the
   * builder of the subclass, and the next call in the chain sees every key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class MetricsPropertiesBuilder<C extends MetricsProperties, B extends MetricsProperties.MetricsPropertiesBuilder<C, B>> {

    /**
     * How long the measurement of a gauge which has to ask somebody is reused, before it is
     * taken again. The builder starts from the same value as the field.
     */
    private Duration gaugeCache = DEFAULT_GAUGE_CACHE;

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one.
     * {@link MetricsProperties#builder()} hands out the builder of this class.
     */
    public MetricsPropertiesBuilder() {
    }

    /**
     * How long the measurement of a gauge which has to ask somebody is reused, before it is
     * taken again.
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

      return "MetricsProperties.MetricsPropertiesBuilder("
          + "gaugeCache="
          + gaugeCache
          + ")";

    }

  }

  /**
   * The builder which {@link #builder()} hands out: the one which builds
   * {@link MetricsProperties} itself, and not a subclass of it.
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
   * Every builder of this class and of its subclasses builds through this. It is the one
   * place where the values of this class move from the builder into the object. So a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected MetricsProperties(
      final MetricsProperties.MetricsPropertiesBuilder<?, ?> b) {

    this.gaugeCache = b.gaugeCache;

  }

  /**
   * A builder of {@link MetricsProperties}. It is empty, except for the values which have a
   * default.
   *
   * @return The builder
   */
  public static MetricsProperties.MetricsPropertiesBuilder<?, ?> builder() {

    return new MetricsProperties.MetricsPropertiesBuilderImpl();

  }

  /**
   * How long the measurement of a gauge which has to ask somebody is reused, before it is
   * taken again.
   *
   * @return The value of {@link #gaugeCache}
   */
  public Duration getGaugeCache() {

    return gaugeCache;

  }

  /**
   * How long the measurement of a gauge which has to ask somebody is reused, before it is
   * taken again.
   *
   * @param gaugeCache The value of {@link #gaugeCache}
   */
  public void setGaugeCache(
      final Duration gaugeCache) {

    this.gaugeCache = gaugeCache;

  }

}
