package io.vanillabp.integration.adapter.migration.config;

/**
 * Configuration of what VanillaBP does when the store of a workflow aggregate is not
 * covered by the transaction it opens (properties section
 * <code>vanillabp.transactions</code>, overridable per workflow module as
 * <code>vanillabp.workflow-modules.&lt;id&gt;.transactions</code>).
 * <p>
 * The only setting is whether unguarded writes are accepted. VanillaBP refuses to boot
 * where a platform can name both the defect and its fix - a MongoDB-managed aggregate in
 * an application whose only transaction manager is a JPA one is the case this exists for.
 * An application which knowingly wants that behaviour states it here, and the message
 * stays as a WARN so the decision remains visible in the log.
 */
public class TransactionsProperties {

  /**
   * The empty section a configuration binder starts from: both platforms create the object
   * and then write the keys the application configured into it, one setter per key.
   * <p>
   * It asks the builder for the values, which is how a field given a default one day
   * keeps it: the default then stands on the builder as well, and this constructor reads
   * it from there.
   */
  public TransactionsProperties() {

    this(builder());

  }

  /**
   * What VanillaBP does about a workflow aggregate whose store is demonstrably not
   * covered by the transaction it opens.
   */
  public enum UnguardedAggregateWrites {

    /**
     * The boot ends with a guiding message naming the fix. The default.
     */
    REJECTED,

    /**
     * The application accepts writes which do not commit or roll back together. The
     * message is logged as a WARN instead of ending the boot.
     */
    ACCEPTED

  }

  /**
   * Whether writes to a store outside VanillaBP's transaction are accepted.
   * <code>null</code> in a workflow module's section means "whatever is configured
   * globally"; the default of the global section is
   * {@link UnguardedAggregateWrites#REJECTED}.
   */
  private UnguardedAggregateWrites unguardedAggregateWrites;

  /**
   * The builder of {@link TransactionsProperties}. Its two type parameters carry the
   * class being built and the builder itself, so a call inherited from a base class
   * comes back as the builder of the subclass and the next call in the chain sees every
   * key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class TransactionsPropertiesBuilder<C extends TransactionsProperties, B extends TransactionsProperties.TransactionsPropertiesBuilder<C, B>> {

    /**
     * Whether writes to a store outside VanillaBP's transaction are accepted.
     */
    private UnguardedAggregateWrites unguardedAggregateWrites;

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one:
     * {@link TransactionsProperties#builder()} hands out the builder of this class.
     */
    public TransactionsPropertiesBuilder() {
    }

    /**
     * Whether writes to a store outside VanillaBP's transaction are accepted.
     *
     * @param unguardedAggregateWrites The value of {@link #unguardedAggregateWrites}
     * @return This builder, so the calls chain
     */
    public B unguardedAggregateWrites(
        final UnguardedAggregateWrites unguardedAggregateWrites) {

      this.unguardedAggregateWrites = unguardedAggregateWrites;
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

      return "TransactionsProperties.TransactionsPropertiesBuilder("
          + "unguardedAggregateWrites="
          + unguardedAggregateWrites
          + ")";

    }

  }

  /**
   * The builder {@link #builder()} hands out: the one which builds
   * {@link TransactionsProperties} itself rather than a subclass of it.
   */
  private static final class TransactionsPropertiesBuilderImpl extends TransactionsProperties.TransactionsPropertiesBuilder<TransactionsProperties, TransactionsProperties.TransactionsPropertiesBuilderImpl> {

    /**
     * Nobody but {@link TransactionsProperties#builder()} builds one.
     */
    private TransactionsPropertiesBuilderImpl() {
    }

    /**
     * This builder, typed as itself.
     *
     * @return This builder
     */
    @Override
    protected TransactionsProperties.TransactionsPropertiesBuilderImpl self() {

      return this;

    }

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public TransactionsProperties build() {

      return new TransactionsProperties(this);

    }

  }

  /**
   * What every builder of this class and of its subclasses builds through. It is the one
   * place the values of this class move from the builder into the object, so a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected TransactionsProperties(
      final TransactionsProperties.TransactionsPropertiesBuilder<?, ?> b) {

    this.unguardedAggregateWrites = b.unguardedAggregateWrites;

  }

  /**
   * A builder of {@link TransactionsProperties}, empty except for the values which have
   * a default.
   *
   * @return The builder
   */
  public static TransactionsProperties.TransactionsPropertiesBuilder<?, ?> builder() {

    return new TransactionsProperties.TransactionsPropertiesBuilderImpl();

  }

  /**
   * Whether writes to a store outside VanillaBP's transaction are accepted.
   *
   * @return The value of {@link #unguardedAggregateWrites}
   */
  public UnguardedAggregateWrites getUnguardedAggregateWrites() {

    return unguardedAggregateWrites;

  }

  /**
   * Whether writes to a store outside VanillaBP's transaction are accepted.
   *
   * @param unguardedAggregateWrites The value of {@link #unguardedAggregateWrites}
   */
  public void setUnguardedAggregateWrites(
      final UnguardedAggregateWrites unguardedAggregateWrites) {

    this.unguardedAggregateWrites = unguardedAggregateWrites;

  }

}
