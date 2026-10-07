package io.vanillabp.integration.adapter.migration.config;

import java.util.Map;

/**
 * Properties of a single BPMN task of a workflow
 * (properties section
 * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.tasks.&lt;task&gt;.*</code>).
 * The task level is the MOST specific level of the most-specific-wins resolution of
 * adapter-scoped properties (see
 * {@link MigrationAdapterProperties#resolveForAdapter}).
 * <p>
 * It is read where a single task is the reason for a setting: whether the deliveries of
 * that task are deduplicated (<code>deduplicate-deliveries</code> of
 * {@link AdapterProperties}) and how long it may stay open (<code>max-task-age</code> of
 * {@link DeliveryProperties}). An adapter setting of its own, a per-task job timeout say,
 * needs nothing here beyond its key.
 * <p>
 * Why an adapter setting can be written at four levels, and which of them wins, is decision 7 in
 * the repository's DECISIONS.md.
 */
public class TaskAdapterProperties {

  /**
   * The empty section a configuration binder starts from, one per task an application
   * writes something about: both platforms create the object and then write the keys into
   * it, one setter per key.
   * <p>
   * It asks the builder for the values, and that is not a detour: every default of this
   * class stands on the builder as well as on the field, and both ways into an object
   * end here. A constructor which set the fields itself would have to repeat every
   * default, and the first one somebody forgets would leave the maps below
   * <code>null</code> instead of empty.
   */
  public TaskAdapterProperties() {

    this(builder());

  }

  /**
   * The properties of adapters specific to this task. Keys are the adapter IDs.
   */
  private Map<String, AdapterProperties> adapters = Map.of();

  /**
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this task - the
   * most specific level an extension setting may be written at. Keys are the extension
   * ids, and a key the task says nothing about keeps what the three less specific levels
   * say (see {@link MigrationAdapterProperties#resolveForExtension}).
   */
  private Map<String, Map<String, String>> extensions = Map.of();

  /**
   * The <code>&#64;TaskParam</code> parameters of this task whose type the developer
   * declared (<code>declared-task-params</code>), by the name the input mapping gives the
   * value. This is the most specific of the four levels the declaration may be written at,
   * and it is where it belongs: a <code>&#64;TaskParam</code> belongs to one task.
   */
  private java.util.List<String> declaredTaskParams;

  /**
   * Whether something other than this application serves this task
   * (<code>implemented-externally</code>), so it needs no <code>&#64;WorkflowTask</code>
   * method. The task is named by its element id or by its task definition, and the
   * element id wins where both are written. <code>null</code> means "not configured at
   * this level" (see {@link MigrationAdapterProperties#implementedExternally}).
   */
  private Boolean implementedExternally;

  /**
   * Overrides <code>vanillabp.delivery</code> for this task - the most specific level
   * the maximum age of an open task may be set at, which is where it belongs: the one
   * task waiting for a signature is the reason the whole application does not get a
   * longer age.
   */
  private DeliveryProperties delivery;

  /**
   * The builder of {@link TaskAdapterProperties}. Its two type parameters carry the
   * class being built and the builder itself, so a call inherited from a base class
   * comes back as the builder of the subclass and the next call in the chain sees every
   * key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class TaskAdapterPropertiesBuilder<C extends TaskAdapterProperties, B extends TaskAdapterProperties.TaskAdapterPropertiesBuilder<C, B>> {

    /**
     * The properties of adapters specific to this task. The builder starts from the same
     * value the field does.
     */
    private Map<String, AdapterProperties> adapters = Map.of();

    /**
     * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this task -
     * the most specific level an extension setting may be written at. The builder starts
     * from the same value the field does.
     */
    private Map<String, Map<String, String>> extensions = Map.of();

    /**
     * The <code>&#64;TaskParam</code> parameters of this task whose type the developer
     * declared (<code>declared-task-params</code>), by the name the input mapping gives
     * the value.
     */
    private java.util.List<String> declaredTaskParams;

    /**
     * Whether something other than this application serves this task
     * (<code>implemented-externally</code>), so it needs no <code>&#64;WorkflowTask</code>
     * method. The task is named by its element id or by its task definition, and the
     * element id wins where both are written. <code>null</code> means "not configured at
     * this level" (see {@link MigrationAdapterProperties#implementedExternally}).
     */
    private Boolean implementedExternally;

    /**
     * Overrides <code>vanillabp.delivery</code> for this task - the most specific level
     * the maximum age of an open task may be set at, which is where it belongs: the one
     * task waiting for a signature is the reason the whole application does not get a
     * longer age.
     */
    private DeliveryProperties delivery;

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one:
     * {@link TaskAdapterProperties#builder()} hands out the builder of this class.
     */
    public TaskAdapterPropertiesBuilder() {
    }

    /**
     * The properties of adapters specific to this task.
     *
     * @param adapters The value of {@link #adapters}
     * @return This builder, so the calls chain
     */
    public B adapters(
        final Map<String, AdapterProperties> adapters) {

      this.adapters = adapters;
      return self();

    }

    /**
     * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this task -
     * the most specific level an extension setting may be written at.
     *
     * @param extensions The value of {@link #extensions}
     * @return This builder, so the calls chain
     */
    public B extensions(
        final Map<String, Map<String, String>> extensions) {

      this.extensions = extensions;
      return self();

    }

    /**
     * The <code>&#64;TaskParam</code> parameters of this task whose type the developer
     * declared (<code>declared-task-params</code>), by the name the input mapping gives
     * the value.
     *
     * @param declaredTaskParams The value of {@link #declaredTaskParams}
     * @return This builder, so the calls chain
     */
    public B declaredTaskParams(
        final java.util.List<String> declaredTaskParams) {

      this.declaredTaskParams = declaredTaskParams;
      return self();

    }

    /**
     * Whether something other than this application serves this task
     * (<code>implemented-externally</code>), so it needs no <code>&#64;WorkflowTask</code>
     * method. The task is named by its element id or by its task definition, and the
     * element id wins where both are written. <code>null</code> means "not configured at
     * this level" (see {@link MigrationAdapterProperties#implementedExternally}).
     *
     * @param implementedExternally The value of {@link #implementedExternally}
     * @return This builder, so the calls chain
     */
    public B implementedExternally(
        final Boolean implementedExternally) {

      this.implementedExternally = implementedExternally;
      return self();

    }

    /**
     * Overrides <code>vanillabp.delivery</code> for this task - the most specific level
     * the maximum age of an open task may be set at, which is where it belongs: the one
     * task waiting for a signature is the reason the whole application does not get a
     * longer age.
     *
     * @param delivery The value of {@link #delivery}
     * @return This builder, so the calls chain
     */
    public B delivery(
        final DeliveryProperties delivery) {

      this.delivery = delivery;
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

      return "TaskAdapterProperties.TaskAdapterPropertiesBuilder("
          + "adapters="
          + adapters
          + ", "
          + "extensions="
          + extensions
          + ", "
          + "declaredTaskParams="
          + declaredTaskParams
          + ", "
          + "implementedExternally="
          + implementedExternally
          + ", "
          + "delivery="
          + delivery
          + ")";

    }

  }

  /**
   * The builder {@link #builder()} hands out: the one which builds
   * {@link TaskAdapterProperties} itself rather than a subclass of it.
   */
  private static final class TaskAdapterPropertiesBuilderImpl extends TaskAdapterProperties.TaskAdapterPropertiesBuilder<TaskAdapterProperties, TaskAdapterProperties.TaskAdapterPropertiesBuilderImpl> {

    /**
     * Nobody but {@link TaskAdapterProperties#builder()} builds one.
     */
    private TaskAdapterPropertiesBuilderImpl() {
    }

    /**
     * This builder, typed as itself.
     *
     * @return This builder
     */
    @Override
    protected TaskAdapterProperties.TaskAdapterPropertiesBuilderImpl self() {

      return this;

    }

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public TaskAdapterProperties build() {

      return new TaskAdapterProperties(this);

    }

  }

  /**
   * What every builder of this class and of its subclasses builds through. It is the one
   * place the values of this class move from the builder into the object, so a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected TaskAdapterProperties(
      final TaskAdapterProperties.TaskAdapterPropertiesBuilder<?, ?> b) {

    this.adapters = b.adapters;
    this.extensions = b.extensions;
    this.declaredTaskParams = b.declaredTaskParams;
    this.implementedExternally = b.implementedExternally;
    this.delivery = b.delivery;

  }

  /**
   * A builder of {@link TaskAdapterProperties}, empty except for the values which have a
   * default.
   *
   * @return The builder
   */
  public static TaskAdapterProperties.TaskAdapterPropertiesBuilder<?, ?> builder() {

    return new TaskAdapterProperties.TaskAdapterPropertiesBuilderImpl();

  }

  /**
   * The properties of adapters specific to this task.
   *
   * @return The value of {@link #adapters}
   */
  public Map<String, AdapterProperties> getAdapters() {

    return adapters;

  }

  /**
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this task - the
   * most specific level an extension setting may be written at.
   *
   * @return The value of {@link #extensions}
   */
  public Map<String, Map<String, String>> getExtensions() {

    return extensions;

  }

  /**
   * The <code>&#64;TaskParam</code> parameters of this task whose type the developer
   * declared (<code>declared-task-params</code>), by the name the input mapping gives
   * the value.
   *
   * @return The value of {@link #declaredTaskParams}
   */
  public java.util.List<String> getDeclaredTaskParams() {

    return declaredTaskParams;

  }

  /**
   * Whether something other than this application serves this task
   * (<code>implemented-externally</code>), so it needs no <code>&#64;WorkflowTask</code>
   * method. The task is named by its element id or by its task definition, and the
   * element id wins where both are written. <code>null</code> means "not configured at
   * this level" (see {@link MigrationAdapterProperties#implementedExternally}).
   *
   * @return The value of {@link #implementedExternally}
   */
  public Boolean getImplementedExternally() {

    return implementedExternally;

  }

  /**
   * Overrides <code>vanillabp.delivery</code> for this task - the most specific level
   * the maximum age of an open task may be set at, which is where it belongs: the one
   * task waiting for a signature is the reason the whole application does not get a
   * longer age.
   *
   * @return The value of {@link #delivery}
   */
  public DeliveryProperties getDelivery() {

    return delivery;

  }

  /**
   * The properties of adapters specific to this task.
   *
   * @param adapters The value of {@link #adapters}
   */
  public void setAdapters(
      final Map<String, AdapterProperties> adapters) {

    this.adapters = adapters;

  }

  /**
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this task - the
   * most specific level an extension setting may be written at.
   *
   * @param extensions The value of {@link #extensions}
   */
  public void setExtensions(
      final Map<String, Map<String, String>> extensions) {

    this.extensions = extensions;

  }

  /**
   * The <code>&#64;TaskParam</code> parameters of this task whose type the developer
   * declared (<code>declared-task-params</code>), by the name the input mapping gives
   * the value.
   *
   * @param declaredTaskParams The value of {@link #declaredTaskParams}
   */
  public void setDeclaredTaskParams(
      final java.util.List<String> declaredTaskParams) {

    this.declaredTaskParams = declaredTaskParams;

  }

  /**
   * Whether something other than this application serves this task
   * (<code>implemented-externally</code>), so it needs no <code>&#64;WorkflowTask</code>
   * method. The task is named by its element id or by its task definition, and the
   * element id wins where both are written. <code>null</code> means "not configured at
   * this level" (see {@link MigrationAdapterProperties#implementedExternally}).
   *
   * @param implementedExternally The value of {@link #implementedExternally}
   */
  public void setImplementedExternally(
      final Boolean implementedExternally) {

    this.implementedExternally = implementedExternally;

  }

  /**
   * Overrides <code>vanillabp.delivery</code> for this task - the most specific level
   * the maximum age of an open task may be set at, which is where it belongs: the one
   * task waiting for a signature is the reason the whole application does not get a
   * longer age.
   *
   * @param delivery The value of {@link #delivery}
   */
  public void setDelivery(
      final DeliveryProperties delivery) {

    this.delivery = delivery;

  }

}
