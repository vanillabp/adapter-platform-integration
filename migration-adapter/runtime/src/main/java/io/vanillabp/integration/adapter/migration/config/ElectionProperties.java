package io.vanillabp.integration.adapter.migration.config;

/**
 * The configuration of what VanillaBP does when an adapter cannot find workflows at all
 * (properties section <code>vanillabp.election</code>, which can be overridden per workflow
 * module as <code>vanillabp.workflow-modules.&lt;id&gt;.election</code>).
 * <p>
 * Finding the BPMS which holds a workflow is a walk over the prioritized adapters. It is
 * exactly as right as the answers it gets. An adapter which cannot ask its BPMS answers
 * optimistically. Examples are a Camunda 8 cluster without secondary storage, and the
 * Process-Engine-API, which has no query API at all. That answer is correct while it is the
 * only BPMS configured, and a guess as soon as it is not. VanillaBP refuses to boot such a
 * combination. An application which wants it anyway says so here.
 */
public class ElectionProperties {

  /**
   * The empty section from which a configuration binder starts. Both platforms create the
   * object, and then write the keys which the application configured into it, one setter per
   * key.
   * <p>
   * It asks the builder for the values. That is how a field which gets a default one day
   * keeps it. The default is then on the builder as well, and this constructor reads it from
   * there.
   */
  public ElectionProperties() {

    this(builder());

  }

  /**
   * What VanillaBP does about a prioritized adapter which cannot find workflows, next to at
   * least one other adapter.
   */
  public enum GuessingAdapters {

    /**
     * The boot ends with a guiding message which names the adapter and the fix. This is the
     * default.
     */
    REJECTED,

    /**
     * The application accepts that operations on existing workflows are routed by the order
     * of the list, and not by an answer. The message is logged as a WARN, and the boot goes
     * on.
     */
    ACCEPTED

  }

  /**
   * Whether an adapter which has to guess is accepted next to others. <code>null</code> in
   * the section of a workflow module means "whatever is configured globally". The default of
   * the global section is {@link GuessingAdapters#REJECTED}.
   */
  private GuessingAdapters guessingAdapters;

  /**
   * The builder of {@link ElectionProperties}. Its two type parameters are the class being
   * built and the builder itself. So a call inherited from a base class comes back as the
   * builder of the subclass, and the next call in the chain sees every key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class ElectionPropertiesBuilder<C extends ElectionProperties, B extends ElectionProperties.ElectionPropertiesBuilder<C, B>> {

    /**
     * Whether an adapter which has to guess is accepted next to others.
     */
    private GuessingAdapters guessingAdapters;

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one.
     * {@link ElectionProperties#builder()} hands out the builder of this class.
     */
    public ElectionPropertiesBuilder() {
    }

    /**
     * Whether an adapter which has to guess is accepted next to others.
     *
     * @param guessingAdapters The value of {@link #guessingAdapters}
     * @return This builder, so the calls chain
     */
    public B guessingAdapters(
        final GuessingAdapters guessingAdapters) {

      this.guessingAdapters = guessingAdapters;
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

      return "ElectionProperties.ElectionPropertiesBuilder("
          + "guessingAdapters="
          + guessingAdapters
          + ")";

    }

  }

  /**
   * The builder which {@link #builder()} hands out: the one which builds
   * {@link ElectionProperties} itself, and not a subclass of it.
   */
  private static final class ElectionPropertiesBuilderImpl extends ElectionProperties.ElectionPropertiesBuilder<ElectionProperties, ElectionProperties.ElectionPropertiesBuilderImpl> {

    /**
     * Nobody but {@link ElectionProperties#builder()} builds one.
     */
    private ElectionPropertiesBuilderImpl() {
    }

    /**
     * This builder, typed as itself.
     *
     * @return This builder
     */
    @Override
    protected ElectionProperties.ElectionPropertiesBuilderImpl self() {

      return this;

    }

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public ElectionProperties build() {

      return new ElectionProperties(this);

    }

  }

  /**
   * Every builder of this class and of its subclasses builds through this. It is the one
   * place where the values of this class move from the builder into the object. So a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected ElectionProperties(
      final ElectionProperties.ElectionPropertiesBuilder<?, ?> b) {

    this.guessingAdapters = b.guessingAdapters;

  }

  /**
   * A builder of {@link ElectionProperties}. It is empty, except for the values which have a
   * default.
   *
   * @return The builder
   */
  public static ElectionProperties.ElectionPropertiesBuilder<?, ?> builder() {

    return new ElectionProperties.ElectionPropertiesBuilderImpl();

  }

  /**
   * Whether an adapter which has to guess is accepted next to others.
   *
   * @return The value of {@link #guessingAdapters}
   */
  public GuessingAdapters getGuessingAdapters() {

    return guessingAdapters;

  }

  /**
   * Whether an adapter which has to guess is accepted next to others.
   *
   * @param guessingAdapters The value of {@link #guessingAdapters}
   */
  public void setGuessingAdapters(
      final GuessingAdapters guessingAdapters) {

    this.guessingAdapters = guessingAdapters;

  }

}
