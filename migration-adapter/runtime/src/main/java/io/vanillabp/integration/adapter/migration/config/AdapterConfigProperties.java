package io.vanillabp.integration.adapter.migration.config;

/**
 * The configuration of one adapter instance
 * (properties section <code>vanillabp.adapters.&lt;id&gt;.*</code>).
 * <p>
 * The properties modeled here are the ones which the migration adapter owns, and they do not
 * depend on the platform. BPMS adapters add their own keys to the same properties section
 * (for example connection settings). They bind their own view of the <code>vanillabp.*</code>
 * tree for that. Those keys are not modeled here.
 * <p>
 * It extends {@link AdapterProperties}. For adapter-scoped properties the most specific level
 * wins, and this section is the least specific level (see
 * {@link MigrationAdapterProperties#resolveForAdapter}). So it has the same keys per level as
 * the workflow-module, workflow and task levels.
 */
public class AdapterConfigProperties extends AdapterProperties {

  /**
   * The empty section from which a configuration binder starts, one per configured adapter
   * id. Both platforms create the object, and then write the keys which the application
   * configured into it, one setter per key.
   * <p>
   * It asks the builder for the values, and that is not a detour. Every default of this class
   * is on the builder as well as on the field, and both ways into an object end here. A
   * constructor which set the fields itself would have to repeat every default. The first
   * default somebody forgets would leave such a field <code>null</code>.
   */
  public AdapterConfigProperties() {

    this(builder());

  }

  /**
   * The adapter's type for a custom adapter identifier, or null for an identifier which is
   * not custom. Then the adapter's ID is the type (see
   * {@link MigrationAdapterProperties#adapterTypes()}).
   */
  private String type;

  /**
   * How to treat a failed deployment of BPMS resources for this adapter.
   * {@link DeploymentFailurePolicy#FAIL} (the default) stops the boot of the application.
   * {@link DeploymentFailurePolicy#WARN} logs the failure of an adapter which is NOT first
   * priority, and the application still starts. A failure of the first-priority adapter
   * always fails the boot.
   */
  private DeploymentFailurePolicy deploymentFailure;

  /**
   * Creates an adapter configuration of the given type.
   *
   * @param type The adapter's type
   * @return The adapter configuration
   */
  public static AdapterConfigProperties ofType(
      final String type) {

    return AdapterConfigProperties
        .builder()
        .type(type)
        .build();

  }

  /**
   * The builder of {@link AdapterConfigProperties}. Its two type parameters are the class
   * being built and the builder itself. So a call inherited from a base class comes back as
   * the builder of the subclass, and the next call in the chain sees every key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class AdapterConfigPropertiesBuilder<C extends AdapterConfigProperties, B extends AdapterConfigProperties.AdapterConfigPropertiesBuilder<C, B>> extends AdapterProperties.AdapterPropertiesBuilder<C, B> {

    /**
     * The adapter's type for a custom adapter identifier, or null for an identifier which is
     * not custom. Then the adapter's ID is the type (see
     * {@link MigrationAdapterProperties#adapterTypes()}).
     */
    private String type;

    /**
     * How to treat a failed deployment of BPMS resources for this adapter.
     * {@link DeploymentFailurePolicy#FAIL} (the default) stops the boot of the application.
     * {@link DeploymentFailurePolicy#WARN} logs the failure of an adapter which is NOT first
     * priority, and the application still starts. A failure of the first-priority adapter
     * always fails the boot.
     */
    private DeploymentFailurePolicy deploymentFailure;

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one.
     * {@link AdapterConfigProperties#builder()} hands out the builder of this class.
     */
    public AdapterConfigPropertiesBuilder() {
    }

    /**
     * The adapter's type for a custom adapter identifier, or null for an identifier which is
     * not custom. Then the adapter's ID is the type (see
     * {@link MigrationAdapterProperties#adapterTypes()}).
     *
     * @param type The value of {@link #type}
     * @return This builder, so the calls chain
     */
    public B type(
        final String type) {

      this.type = type;
      return self();

    }

    /**
     * How to treat a failed deployment of BPMS resources for this adapter.
     * {@link DeploymentFailurePolicy#FAIL} (the default) stops the boot of the application.
     * {@link DeploymentFailurePolicy#WARN} logs the failure of an adapter which is NOT first
     * priority, and the application still starts. A failure of the first-priority adapter
     * always fails the boot.
     *
     * @param deploymentFailure The value of {@link #deploymentFailure}
     * @return This builder, so the calls chain
     */
    public B deploymentFailure(
        final DeploymentFailurePolicy deploymentFailure) {

      this.deploymentFailure = deploymentFailure;
      return self();

    }

    /**
     * The builder itself, typed as the builder of the subclass. Every method of the chain
     * returns it. That keeps a chain which started on a subclass builder at that subclass.
     *
     * @return This builder
     */
    @Override
    protected abstract B self();

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public abstract C build();

    /**
     * What this builder holds, for a message and for a debugger.
     *
     * @return The name of this builder and every value written into it
     */
    @Override
    public String toString() {

      return "AdapterConfigProperties.AdapterConfigPropertiesBuilder("
          + "super="
          + super.toString()
          + ", "
          + "type="
          + type
          + ", "
          + "deploymentFailure="
          + deploymentFailure
          + ")";

    }

  }

  /**
   * The builder which {@link #builder()} hands out: the one which builds
   * {@link AdapterConfigProperties} itself, and not a subclass of it.
   */
  private static final class AdapterConfigPropertiesBuilderImpl extends AdapterConfigProperties.AdapterConfigPropertiesBuilder<AdapterConfigProperties, AdapterConfigProperties.AdapterConfigPropertiesBuilderImpl> {

    /**
     * Nobody but {@link AdapterConfigProperties#builder()} builds one.
     */
    private AdapterConfigPropertiesBuilderImpl() {
    }

    /**
     * This builder, typed as itself.
     *
     * @return This builder
     */
    @Override
    protected AdapterConfigProperties.AdapterConfigPropertiesBuilderImpl self() {

      return this;

    }

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public AdapterConfigProperties build() {

      return new AdapterConfigProperties(this);

    }

  }

  /**
   * Every builder of this class and of its subclasses builds through this. It is the one
   * place where the values of this class move from the builder into the object. So a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected AdapterConfigProperties(
      final AdapterConfigProperties.AdapterConfigPropertiesBuilder<?, ?> b) {

    super(b);

    this.type = b.type;
    this.deploymentFailure = b.deploymentFailure;

  }

  /**
   * A builder of {@link AdapterConfigProperties}. It is empty, except for the values which
   * have a default.
   *
   * @return The builder
   */
  public static AdapterConfigProperties.AdapterConfigPropertiesBuilder<?, ?> builder() {

    return new AdapterConfigProperties.AdapterConfigPropertiesBuilderImpl();

  }

  /**
   * The adapter's type for a custom adapter identifier, or null for an identifier which is
   * not custom. Then the adapter's ID is the type (see
   * {@link MigrationAdapterProperties#adapterTypes()}).
   *
   * @return The value of {@link #type}
   */
  public String getType() {

    return type;

  }

  /**
   * How to treat a failed deployment of BPMS resources for this adapter.
   * {@link DeploymentFailurePolicy#FAIL} (the default) stops the boot of the application.
   * {@link DeploymentFailurePolicy#WARN} logs the failure of an adapter which is NOT first
   * priority, and the application still starts. A failure of the first-priority adapter
   * always fails the boot.
   *
   * @return The value of {@link #deploymentFailure}
   */
  public DeploymentFailurePolicy getDeploymentFailure() {

    return deploymentFailure;

  }

  /**
   * The adapter's type for a custom adapter identifier, or null for an identifier which is
   * not custom. Then the adapter's ID is the type (see
   * {@link MigrationAdapterProperties#adapterTypes()}).
   *
   * @param type The value of {@link #type}
   */
  public void setType(
      final String type) {

    this.type = type;

  }

  /**
   * How to treat a failed deployment of BPMS resources for this adapter.
   * {@link DeploymentFailurePolicy#FAIL} (the default) stops the boot of the application.
   * {@link DeploymentFailurePolicy#WARN} logs the failure of an adapter which is NOT first
   * priority, and the application still starts. A failure of the first-priority adapter
   * always fails the boot.
   *
   * @param deploymentFailure The value of {@link #deploymentFailure}
   */
  public void setDeploymentFailure(
      final DeploymentFailurePolicy deploymentFailure) {

    this.deploymentFailure = deploymentFailure;

  }

}
