package io.vanillabp.integration.adapter.migration.config;

import java.util.List;

/**
 * The one key every level of the configuration carries: which adapters serve, and in which
 * order. The global section extends this class, and so do the section of a workflow module
 * ({@link WorkflowModuleAdapterProperties}) and the section of a workflow
 * ({@link WorkflowAdapterProperties}).
 */
public class AdaptersConfigurationProperties {

  /**
   * Which adapters serve the workflows of this level, and in which order they are asked
   * where several of them could serve - the first one which says it holds the workflow
   * runs the {@link io.vanillabp.spi.process.ProcessService} method, and the first one of
   * the list starts a new workflow.
   * <p>
   * The key is <code>vanillabp.prioritized-adapters</code>,
   * <code>vanillabp.workflow-modules.&lt;module&gt;.prioritized-adapters</code> or
   * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.prioritized-adapters</code>,
   * and the most specific level which names one adapter or more wins as a whole - see
   * {@link MigrationAdapterProperties#getPrioritizedAdaptersFor(String, String)}.
   * <p>
   * Empty by default, which means "this level says nothing". An application whose
   * classpath holds exactly one adapter has the list derived for it and configures
   * nothing (decision 8 in the repository's DECISIONS.md); where no level names an
   * adapter for a workflow, the startup ends with a message naming the three keys.
   */
  private List<String> prioritizedAdapters = List.of();

  /**
   * The empty section a configuration binder starts from: both platforms create the object
   * and then write the keys the application configured into it, one setter per key.
   * <p>
   * It asks the builder for the values, and that is not a detour: every default of this
   * class stands on the builder as well as on the field, and both ways into an object
   * end here. A constructor which set the fields itself would have to repeat every
   * default, and the first one somebody forgets would leave such a field
   * <code>null</code>.
   */
  public AdaptersConfigurationProperties() {

    this(builder());

  }

  /**
   * The builder of {@link AdaptersConfigurationProperties}. Its two type parameters
   * carry the class being built and the builder itself, so a call inherited from a base
   * class comes back as the builder of the subclass and the next call in the chain sees
   * every key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class AdaptersConfigurationPropertiesBuilder<C extends AdaptersConfigurationProperties, B extends AdaptersConfigurationProperties.AdaptersConfigurationPropertiesBuilder<C, B>> {

    /**
     * Which adapters serve the workflows of this level, and in which order they are
     * asked where several of them could serve - the first one which says it holds the
     * workflow runs the {@link io.vanillabp.spi.process.ProcessService} method, and the
     * first one of the list starts a new workflow. The builder starts from the same
     * value the field does.
     */
    private List<String> prioritizedAdapters = List.of();

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one:
     * {@link AdaptersConfigurationProperties#builder()} hands out the builder of this
     * class.
     */
    public AdaptersConfigurationPropertiesBuilder() {
    }

    /**
     * Which adapters serve the workflows of this level, and in which order they are
     * asked where several of them could serve - the first one which says it holds the
     * workflow runs the {@link io.vanillabp.spi.process.ProcessService} method, and the
     * first one of the list starts a new workflow.
     *
     * @param prioritizedAdapters The value of {@link #prioritizedAdapters}
     * @return This builder, so the calls chain
     */
    public B prioritizedAdapters(
        final List<String> prioritizedAdapters) {

      this.prioritizedAdapters = prioritizedAdapters;
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

      return "AdaptersConfigurationProperties.AdaptersConfigurationPropertiesBuilder("
          + "prioritizedAdapters="
          + prioritizedAdapters
          + ")";

    }

  }

  /**
   * The builder {@link #builder()} hands out: the one which builds
   * {@link AdaptersConfigurationProperties} itself rather than a subclass of it.
   */
  private static final class AdaptersConfigurationPropertiesBuilderImpl extends AdaptersConfigurationProperties.AdaptersConfigurationPropertiesBuilder<AdaptersConfigurationProperties, AdaptersConfigurationProperties.AdaptersConfigurationPropertiesBuilderImpl> {

    /**
     * Nobody but {@link AdaptersConfigurationProperties#builder()} builds one.
     */
    private AdaptersConfigurationPropertiesBuilderImpl() {
    }

    /**
     * This builder, typed as itself.
     *
     * @return This builder
     */
    @Override
    protected AdaptersConfigurationProperties.AdaptersConfigurationPropertiesBuilderImpl self() {

      return this;

    }

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public AdaptersConfigurationProperties build() {

      return new AdaptersConfigurationProperties(this);

    }

  }

  /**
   * What every builder of this class and of its subclasses builds through. It is the one
   * place the values of this class move from the builder into the object, so a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected AdaptersConfigurationProperties(
      final AdaptersConfigurationProperties.AdaptersConfigurationPropertiesBuilder<?, ?> b) {

    this.prioritizedAdapters = b.prioritizedAdapters;

  }

  /**
   * A builder of {@link AdaptersConfigurationProperties}, empty except for the values
   * which have a default.
   *
   * @return The builder
   */
  public static AdaptersConfigurationProperties.AdaptersConfigurationPropertiesBuilder<?, ?> builder() {

    return new AdaptersConfigurationProperties.AdaptersConfigurationPropertiesBuilderImpl();

  }

  /**
   * Which adapters serve the workflows of this level, and in which order they are asked
   * where several of them could serve - the first one which says it holds the workflow
   * runs the {@link io.vanillabp.spi.process.ProcessService} method, and the first one
   * of the list starts a new workflow.
   *
   * @return The value of {@link #prioritizedAdapters}
   */
  public List<String> getPrioritizedAdapters() {

    return prioritizedAdapters;

  }

  /**
   * Which adapters serve the workflows of this level, and in which order they are asked
   * where several of them could serve - the first one which says it holds the workflow
   * runs the {@link io.vanillabp.spi.process.ProcessService} method, and the first one
   * of the list starts a new workflow.
   *
   * @param prioritizedAdapters The value of {@link #prioritizedAdapters}
   */
  public void setPrioritizedAdapters(
      final List<String> prioritizedAdapters) {

    this.prioritizedAdapters = prioritizedAdapters;

  }

}
