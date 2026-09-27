package io.vanillabp.integration.adapter.migration.config;

/**
 * Configuration of what VanillaBP does when an adapter cannot locate workflows at all
 * (properties section <code>vanillabp.election</code>, overridable per workflow module
 * as <code>vanillabp.workflow-modules.&lt;id&gt;.election</code>).
 * <p>
 * Locating the BPMS which holds a workflow is a walk over the prioritized adapters, and
 * it is exactly as right as the answers it gets. An adapter which cannot ask its BPMS -
 * a Camunda 8 cluster without secondary storage, the Process-Engine-API, which has no
 * query API at all - answers optimistically, which is correct while it is the only BPMS
 * configured and a guess as soon as it is not. VanillaBP refuses to boot such a
 * combination, and an application which wants it anyway says so here.
 */
public class ElectionProperties {

  /**
   * The empty section a configuration binder starts from: both platforms create the object
   * and then write the keys the application configured into it, one setter per key.
   * <p>
   * It asks the builder for the values, which is how a field given a default one day
   * keeps it: the default then stands on the builder as well, and this constructor reads
   * it from there.
   */
  public ElectionProperties() {

    this(builder());

  }

  /**
   * What VanillaBP does about a prioritized adapter which cannot locate workflows,
   * next to at least one other adapter.
   */
  public enum GuessingAdapters {

    /**
     * The boot ends with a guiding message naming the adapter and the fix. The
     * default.
     */
    REJECTED,

    /**
     * The application accepts that operations on existing workflows are routed by
     * list order rather than by an answer. The message is logged as a WARN instead of
     * ending the boot.
     */
    ACCEPTED

  }

  /**
   * Whether an adapter which has to guess is accepted next to others.
   * <code>null</code> in a workflow module's section means "whatever is configured
   * globally"; the default of the global section is {@link GuessingAdapters#REJECTED}.
   */
  private GuessingAdapters guessingAdapters;

  /**
   * The builder of {@link ElectionProperties}. Its two type parameters carry the class
   * being built and the builder itself, so a call inherited from a base class comes back
   * as the builder of the subclass and the next call in the chain sees every key again.
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
     * The builder of a subclass calls this while it is built. Nobody else needs one:
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

      return "ElectionProperties.ElectionPropertiesBuilder("
          + "guessingAdapters="
          + guessingAdapters
          + ")";

    }

  }

  /**
   * The builder {@link #builder()} hands out: the one which builds
   * {@link ElectionProperties} itself rather than a subclass of it.
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
   * What every builder of this class and of its subclasses builds through. It is the one
   * place the values of this class move from the builder into the object, so a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected ElectionProperties(
      final ElectionProperties.ElectionPropertiesBuilder<?, ?> b) {

    this.guessingAdapters = b.guessingAdapters;

  }

  /**
   * A builder of {@link ElectionProperties}, empty except for the values which have a
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
