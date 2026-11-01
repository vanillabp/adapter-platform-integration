package io.vanillabp.integration.adapter.migration.config;

import java.util.Map;

/**
 * The properties of a single BPMN task of a workflow (properties section
 * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.tasks.&lt;task&gt;.*</code>).
 * For adapter-scoped properties the most specific level wins, and the task level is the MOST
 * specific one (see {@link MigrationAdapterProperties#resolveForAdapter}).
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
   * The empty section from which a configuration binder starts, one per task about which an
   * application writes something. Both platforms create the object, and then write the keys
   * into it, one setter per key.
   * <p>
   * It asks the builder for the values, and that is not a detour. Every default of this class
   * is on the builder as well as on the field, and both ways into an object end here. A
   * constructor which set the fields itself would have to repeat every default. The first
   * default somebody forgets would leave the maps below <code>null</code> instead of empty.
   */
  public TaskAdapterProperties() {

    this(builder());

  }

  /**
   * The properties of adapters specific to this task. Keys are the adapter IDs.
   */
  private Map<String, AdapterProperties> adapters = Map.of();

  /**
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this task. This is
   * the most specific level at which an extension setting may be written. The keys are the
   * extension ids. A key about which the task says nothing keeps what the three less specific
   * levels say (see {@link MigrationAdapterProperties#resolveForExtension}).
   */
  private Map<String, Map<String, String>> extensions = Map.of();

  /**
   * The <code>&#64;TaskParam</code> parameters of this task whose type the developer declared
   * (<code>declared-task-params</code>), by the name which the input mapping gives the value.
   * This is the most specific of the four levels at which the declaration may be written. It
   * belongs here, because a <code>&#64;TaskParam</code> belongs to one task.
   */
  private java.util.List<String> declaredTaskParams;

  /**
   * Refused here on purpose. A message is correlated to a workflow and not to a task (see
   * {@link MigrationAdapterProperties#messageTimeToLive(String, String, String)}). It is bound
   * at this level, so that a line written here gets a message which says where it belongs,
   * instead of being ignored.
   */
  private java.time.Duration messageTimeToLive;

  /**
   * Whether something other than this application serves this task
   * (<code>implemented-externally</code>). Then it needs no <code>&#64;WorkflowTask</code>
   * method. The task is named by its element id or by its task definition. The element id
   * wins where both are written. <code>null</code> means "not configured at this level" (see
   * {@link MigrationAdapterProperties#implementedExternally}).
   */
  private Boolean implementedExternally;

  /**
   * Overrides <code>vanillabp.delivery</code> for this task. This is the most specific level
   * at which the maximum age of an open task may be set, and it belongs here. The one task
   * which waits for a signature should not give the whole application a longer age.
   */
  private DeliveryProperties delivery;

  /**
   * The builder of {@link TaskAdapterProperties}. Its two type parameters are the class being
   * built and the builder itself. So a call inherited from a base class comes back as the
   * builder of the subclass, and the next call in the chain sees every key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class TaskAdapterPropertiesBuilder<C extends TaskAdapterProperties, B extends TaskAdapterProperties.TaskAdapterPropertiesBuilder<C, B>> {

    /**
     * The properties of adapters specific to this task. The builder starts from the same
     * value as the field.
     */
    private Map<String, AdapterProperties> adapters = Map.of();

    /**
     * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this task. This is
     * the most specific level at which an extension setting may be written. The builder
     * starts from the same value as the field.
     */
    private Map<String, Map<String, String>> extensions = Map.of();

    /**
     * The <code>&#64;TaskParam</code> parameters of this task whose type the developer
     * declared (<code>declared-task-params</code>), by the name which the input mapping gives
     * the value.
     */
    private java.util.List<String> declaredTaskParams;

    /**
     * Refused here on purpose. A message is correlated to a workflow and not to a task.
     */
    private java.time.Duration messageTimeToLive;

    /**
     * Whether something other than this application serves this task
     * (<code>implemented-externally</code>). Then it needs no <code>&#64;WorkflowTask</code>
     * method. The task is named by its element id or by its task definition. The element id
     * wins where both are written. <code>null</code> means "not configured at this level"
     * (see {@link MigrationAdapterProperties#implementedExternally}).
     */
    private Boolean implementedExternally;

    /**
     * Overrides <code>vanillabp.delivery</code> for this task. This is the most specific
     * level at which the maximum age of an open task may be set, and it belongs here. The one
     * task which waits for a signature should not give the whole application a longer age.
     */
    private DeliveryProperties delivery;

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one.
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
     * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this task. This is
     * the most specific level at which an extension setting may be written.
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
     * declared (<code>declared-task-params</code>), by the name which the input mapping gives
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
     * Refused here on purpose. A message is correlated to a workflow and not to a task.
     *
     * @param messageTimeToLive The value of {@link #messageTimeToLive}
     * @return This builder, so the calls chain
     */
    public B messageTimeToLive(
        final java.time.Duration messageTimeToLive) {

      this.messageTimeToLive = messageTimeToLive;
      return self();

    }

    /**
     * Whether something other than this application serves this task
     * (<code>implemented-externally</code>). Then it needs no <code>&#64;WorkflowTask</code>
     * method. The task is named by its element id or by its task definition. The element id
     * wins where both are written. <code>null</code> means "not configured at this level"
     * (see {@link MigrationAdapterProperties#implementedExternally}).
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
     * Overrides <code>vanillabp.delivery</code> for this task. This is the most specific
     * level at which the maximum age of an open task may be set, and it belongs here. The one
     * task which waits for a signature should not give the whole application a longer age.
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
          + "messageTimeToLive="
          + messageTimeToLive
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
   * The builder which {@link #builder()} hands out: the one which builds
   * {@link TaskAdapterProperties} itself, and not a subclass of it.
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
   * Every builder of this class and of its subclasses builds through this. It is the one
   * place where the values of this class move from the builder into the object. So a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected TaskAdapterProperties(
      final TaskAdapterProperties.TaskAdapterPropertiesBuilder<?, ?> b) {

    this.adapters = b.adapters;
    this.extensions = b.extensions;
    this.declaredTaskParams = b.declaredTaskParams;
    this.messageTimeToLive = b.messageTimeToLive;
    this.implementedExternally = b.implementedExternally;
    this.delivery = b.delivery;

  }

  /**
   * A builder of {@link TaskAdapterProperties}. It is empty, except for the values which have
   * a default.
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
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this task. This is
   * the most specific level at which an extension setting may be written.
   *
   * @return The value of {@link #extensions}
   */
  public Map<String, Map<String, String>> getExtensions() {

    return extensions;

  }

  /**
   * The <code>&#64;TaskParam</code> parameters of this task whose type the developer declared
   * (<code>declared-task-params</code>), by the name which the input mapping gives the value.
   *
   * @return The value of {@link #declaredTaskParams}
   */
  public java.util.List<String> getDeclaredTaskParams() {

    return declaredTaskParams;

  }

  /**
   * Refused here on purpose. A message is correlated to a workflow and not to a task.
   *
   * @return The value of {@link #messageTimeToLive}
   */
  public java.time.Duration getMessageTimeToLive() {

    return messageTimeToLive;

  }

  /**
   * Whether something other than this application serves this task
   * (<code>implemented-externally</code>). Then it needs no <code>&#64;WorkflowTask</code>
   * method. The task is named by its element id or by its task definition. The element id
   * wins where both are written. <code>null</code> means "not configured at this level" (see
   * {@link MigrationAdapterProperties#implementedExternally}).
   *
   * @return The value of {@link #implementedExternally}
   */
  public Boolean getImplementedExternally() {

    return implementedExternally;

  }

  /**
   * Overrides <code>vanillabp.delivery</code> for this task. This is the most specific level
   * at which the maximum age of an open task may be set, and it belongs here. The one task
   * which waits for a signature should not give the whole application a longer age.
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
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this task. This is
   * the most specific level at which an extension setting may be written.
   *
   * @param extensions The value of {@link #extensions}
   */
  public void setExtensions(
      final Map<String, Map<String, String>> extensions) {

    this.extensions = extensions;

  }

  /**
   * The <code>&#64;TaskParam</code> parameters of this task whose type the developer declared
   * (<code>declared-task-params</code>), by the name which the input mapping gives the value.
   *
   * @param declaredTaskParams The value of {@link #declaredTaskParams}
   */
  public void setDeclaredTaskParams(
      final java.util.List<String> declaredTaskParams) {

    this.declaredTaskParams = declaredTaskParams;

  }

  /**
   * Refused here on purpose. A message is correlated to a workflow and not to a task.
   *
   * @param messageTimeToLive The value of {@link #messageTimeToLive}
   */
  public void setMessageTimeToLive(
      final java.time.Duration messageTimeToLive) {

    this.messageTimeToLive = messageTimeToLive;

  }

  /**
   * Whether something other than this application serves this task
   * (<code>implemented-externally</code>). Then it needs no <code>&#64;WorkflowTask</code>
   * method. The task is named by its element id or by its task definition. The element id
   * wins where both are written. <code>null</code> means "not configured at this level" (see
   * {@link MigrationAdapterProperties#implementedExternally}).
   *
   * @param implementedExternally The value of {@link #implementedExternally}
   */
  public void setImplementedExternally(
      final Boolean implementedExternally) {

    this.implementedExternally = implementedExternally;

  }

  /**
   * Overrides <code>vanillabp.delivery</code> for this task. This is the most specific level
   * at which the maximum age of an open task may be set, and it belongs here. The one task
   * which waits for a signature should not give the whole application a longer age.
   *
   * @param delivery The value of {@link #delivery}
   */
  public void setDelivery(
      final DeliveryProperties delivery) {

    this.delivery = delivery;

  }

}
