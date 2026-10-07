package io.vanillabp.integration.adapter.migration.config;

import java.util.Map;

/**
 * What an application says about ONE workflow, which is one BPMN process of one workflow
 * module (properties section
 * <code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.*</code>, the
 * key being the plain BPMN process id).
 * <p>
 * This is the third of the four levels an adapter setting may be written at, between the
 * workflow module and the single task (decision 7 in the repository's DECISIONS.md), and it
 * is the only level at which a workflow may be allowed to hand its whole aggregate to the
 * BPMS.
 */
public class WorkflowAdapterProperties extends AdaptersConfigurationProperties {

  /**
   * The BPMN process id this section is about, taken from the key the application wrote it
   * under. Written by {@link MigrationAdapterProperties#validateAndLink()} rather than by
   * the binder, so a section which was built by hand carries it only after that call.
   */
  String bpmnProcessId;

  /**
   * The workflow module this workflow belongs to - the way back up, which is what lets a
   * message about this workflow name its module. Written by
   * {@link MigrationAdapterProperties#validateAndLink()} like the id above.
   */
  WorkflowModuleAdapterProperties workflowModule;

  /**
   * The empty section a configuration binder starts from, one per configured workflow:
   * both platforms create the object and then write the keys the application configured
   * into it, one setter per key.
   * <p>
   * It asks the builder for the values, and that is not a detour: every default of this
   * class stands on the builder as well as on the field, and both ways into an object
   * end here. A constructor which set the fields itself would have to repeat every
   * default, and the first one somebody forgets would leave the maps below
   * <code>null</code> instead of empty.
   */
  public WorkflowAdapterProperties() {

    this(builder());

  }

  /**
   * The properties of adapters specific to this workflow. Keys are the adapter IDs.
   */
  private Map<String, AdapterProperties> adapters = Map.of();

  /**
   * The properties of the workflow's BPMN tasks. Keys are the task IDs (task
   * definitions), and what may stand below one of them is {@link TaskAdapterProperties} -
   * the most specific level of the four.
   */
  private Map<String, TaskAdapterProperties> tasks = Map.of();

  /**
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow -
   * what an extension is told about ONE BPMN process, which is the level below the
   * workflow module. Keys are the extension ids, and a key the workflow says nothing
   * about keeps what the workflow module or the global section says (see
   * {@link MigrationAdapterProperties#resolveForExtension}).
   */
  private Map<String, Map<String, String>> extensions = Map.of();

  /**
   * Whether this workflow may hand its ENTIRE workflow aggregate to the BPMS - the one
   * place this permission may be written, see decision 66 in the repository's
   * DECISIONS.md. An aggregate which keeps nothing back ends the startup until this
   * stands at the workflow it is about.
   */
  private Boolean allowFullSyncWithBpms;

  /**
   * Whether the expressions of this workflow's BPMN model are meant as they are
   * (<code>accept-expressions-in-the-model</code>) - the most specific of the three
   * levels it may be written at, and the level the startup message hands out.
   */
  private Boolean acceptExpressionsInTheModel;

  /**
   * The values of the workflow aggregate this workflow declares for the BPMS
   * (<code>declared-aggregate-values</code>). An entry names a path in the aggregate:
   * <code>amount</code> is the attribute itself, <code>shipping.*</code> is every value
   * below <code>shipping</code>, and <code>shipping.express</code> is one value below it.
   * <p>
   * An entry says that the developer looked at the value, which is what lets a value whose
   * type is neither a <code>boolean</code> nor a text reach the BPMS. It may also say what
   * is shared while the path does not resolve, as in
   * <code>shipping.express=false</code>.
   * <p>
   * Read at the workflow, because the values belong to the aggregate of one workflow.
   */
  private java.util.List<String> declaredAggregateValues;

  /**
   * The <code>&#64;TaskParam</code> parameters of this workflow whose type the developer
   * declared (<code>declared-task-params</code>), by the name the input mapping gives the
   * value. The most specific level wins: the task, then the workflow, then the workflow
   * module, then the application.
   */
  private java.util.List<String> declaredTaskParams;

  /**
   * Whether something other than this application serves every task of this workflow
   * which does not say otherwise (<code>implemented-externally</code>, see
   * {@link MigrationAdapterProperties#implementedExternally}). <code>null</code> means
   * "not configured at this level".
   */
  private Boolean implementedExternally;

  /**
   * Overrides <code>vanillabp.delivery</code> for this workflow. Only the settings which
   * belong to a single workflow are read here - the maximum age of an open task, since
   * one process may wait for a partner for weeks while every other one is done in
   * minutes.
   */
  private DeliveryProperties delivery;

  /**
   * The builder of {@link WorkflowAdapterProperties}. Its two type parameters carry the
   * class being built and the builder itself, so a call inherited from a base class
   * comes back as the builder of the subclass and the next call in the chain sees every
   * key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class WorkflowAdapterPropertiesBuilder<C extends WorkflowAdapterProperties, B extends WorkflowAdapterProperties.WorkflowAdapterPropertiesBuilder<C, B>> extends AdaptersConfigurationProperties.AdaptersConfigurationPropertiesBuilder<C, B> {

    /**
     * The BPMN process id this section is about, taken from the key the application
     * wrote it under.
     */
    private String bpmnProcessId;

    /**
     * The workflow module this workflow belongs to - the way back up, which is what lets
     * a message about this workflow name its module.
     */
    private WorkflowModuleAdapterProperties workflowModule;

    /**
     * The properties of adapters specific to this workflow. The builder starts from the
     * same value the field does.
     */
    private Map<String, AdapterProperties> adapters = Map.of();

    /**
     * The properties of the workflow's BPMN tasks. The builder starts from the same
     * value the field does.
     */
    private Map<String, TaskAdapterProperties> tasks = Map.of();

    /**
     * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow
     * - what an extension is told about ONE BPMN process, which is the level below the
     * workflow module. The builder starts from the same value the field does.
     */
    private Map<String, Map<String, String>> extensions = Map.of();

    /**
     * Whether this workflow may hand its ENTIRE workflow aggregate to the BPMS - the one
     * place this permission may be written, see decision 66 in the repository's
     * DECISIONS.md.
     */
    private Boolean allowFullSyncWithBpms;

    /**
     * Whether the expressions of this workflow's BPMN model are meant as they are
     * (<code>accept-expressions-in-the-model</code>) - the most specific of the three
     * levels it may be written at, and the level the startup message hands out.
     */
    private Boolean acceptExpressionsInTheModel;

    /**
     * The values of the workflow aggregate this workflow declares for the BPMS
     * (<code>declared-aggregate-values</code>).
     */
    private java.util.List<String> declaredAggregateValues;

    /**
     * The <code>&#64;TaskParam</code> parameters of this workflow whose type the
     * developer declared (<code>declared-task-params</code>), by the name the input
     * mapping gives the value.
     */
    private java.util.List<String> declaredTaskParams;

    /**
     * Whether something other than this application serves every task of this workflow
     * which does not say otherwise (<code>implemented-externally</code>, see
     * {@link MigrationAdapterProperties#implementedExternally}). <code>null</code> means
     * "not configured at this level".
     */
    private Boolean implementedExternally;

    /**
     * Overrides <code>vanillabp.delivery</code> for this workflow.
     */
    private DeliveryProperties delivery;

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one:
     * {@link WorkflowAdapterProperties#builder()} hands out the builder of this class.
     */
    public WorkflowAdapterPropertiesBuilder() {
    }

    /**
     * The BPMN process id this section is about, taken from the key the application
     * wrote it under.
     *
     * @param bpmnProcessId The value of {@link #bpmnProcessId}
     * @return This builder, so the calls chain
     */
    public B bpmnProcessId(
        final String bpmnProcessId) {

      this.bpmnProcessId = bpmnProcessId;
      return self();

    }

    /**
     * The workflow module this workflow belongs to - the way back up, which is what lets
     * a message about this workflow name its module.
     *
     * @param workflowModule The value of {@link #workflowModule}
     * @return This builder, so the calls chain
     */
    public B workflowModule(
        final WorkflowModuleAdapterProperties workflowModule) {

      this.workflowModule = workflowModule;
      return self();

    }

    /**
     * The properties of adapters specific to this workflow.
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
     * The properties of the workflow's BPMN tasks.
     *
     * @param tasks The value of {@link #tasks}
     * @return This builder, so the calls chain
     */
    public B tasks(
        final Map<String, TaskAdapterProperties> tasks) {

      this.tasks = tasks;
      return self();

    }

    /**
     * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow
     * - what an extension is told about ONE BPMN process, which is the level below the
     * workflow module.
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
     * Whether this workflow may hand its ENTIRE workflow aggregate to the BPMS - the one
     * place this permission may be written, see decision 66 in the repository's
     * DECISIONS.md.
     *
     * @param allowFullSyncWithBpms The value of {@link #allowFullSyncWithBpms}
     * @return This builder, so the calls chain
     */
    public B allowFullSyncWithBpms(
        final Boolean allowFullSyncWithBpms) {

      this.allowFullSyncWithBpms = allowFullSyncWithBpms;
      return self();

    }

    /**
     * Whether the expressions of this workflow's BPMN model are meant as they are
     * (<code>accept-expressions-in-the-model</code>) - the most specific of the three
     * levels it may be written at, and the level the startup message hands out.
     *
     * @param acceptExpressionsInTheModel The value of
     * {@link #acceptExpressionsInTheModel}
     * @return This builder, so the calls chain
     */
    public B acceptExpressionsInTheModel(
        final Boolean acceptExpressionsInTheModel) {

      this.acceptExpressionsInTheModel = acceptExpressionsInTheModel;
      return self();

    }

    /**
     * The values of the workflow aggregate this workflow declares for the BPMS
     * (<code>declared-aggregate-values</code>).
     *
     * @param declaredAggregateValues The value of {@link #declaredAggregateValues}
     * @return This builder, so the calls chain
     */
    public B declaredAggregateValues(
        final java.util.List<String> declaredAggregateValues) {

      this.declaredAggregateValues = declaredAggregateValues;
      return self();

    }

    /**
     * The <code>&#64;TaskParam</code> parameters of this workflow whose type the
     * developer declared (<code>declared-task-params</code>), by the name the input
     * mapping gives the value.
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
     * Whether something other than this application serves every task of this workflow
     * which does not say otherwise (<code>implemented-externally</code>, see
     * {@link MigrationAdapterProperties#implementedExternally}). <code>null</code> means
     * "not configured at this level".
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
     * Overrides <code>vanillabp.delivery</code> for this workflow.
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

      return "WorkflowAdapterProperties.WorkflowAdapterPropertiesBuilder("
          + "super="
          + super.toString()
          + ", "
          + "bpmnProcessId="
          + bpmnProcessId
          + ", "
          + "workflowModule="
          + workflowModule
          + ", "
          + "adapters="
          + adapters
          + ", "
          + "tasks="
          + tasks
          + ", "
          + "extensions="
          + extensions
          + ", "
          + "allowFullSyncWithBpms="
          + allowFullSyncWithBpms
          + ", "
          + "acceptExpressionsInTheModel="
          + acceptExpressionsInTheModel
          + ", "
          + "declaredAggregateValues="
          + declaredAggregateValues
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
   * {@link WorkflowAdapterProperties} itself rather than a subclass of it.
   */
  private static final class WorkflowAdapterPropertiesBuilderImpl extends WorkflowAdapterProperties.WorkflowAdapterPropertiesBuilder<WorkflowAdapterProperties, WorkflowAdapterProperties.WorkflowAdapterPropertiesBuilderImpl> {

    /**
     * Nobody but {@link WorkflowAdapterProperties#builder()} builds one.
     */
    private WorkflowAdapterPropertiesBuilderImpl() {
    }

    /**
     * This builder, typed as itself.
     *
     * @return This builder
     */
    @Override
    protected WorkflowAdapterProperties.WorkflowAdapterPropertiesBuilderImpl self() {

      return this;

    }

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public WorkflowAdapterProperties build() {

      return new WorkflowAdapterProperties(this);

    }

  }

  /**
   * What every builder of this class and of its subclasses builds through. It is the one
   * place the values of this class move from the builder into the object, so a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected WorkflowAdapterProperties(
      final WorkflowAdapterProperties.WorkflowAdapterPropertiesBuilder<?, ?> b) {

    super(b);

    this.bpmnProcessId = b.bpmnProcessId;
    this.workflowModule = b.workflowModule;
    this.adapters = b.adapters;
    this.tasks = b.tasks;
    this.extensions = b.extensions;
    this.allowFullSyncWithBpms = b.allowFullSyncWithBpms;
    this.acceptExpressionsInTheModel = b.acceptExpressionsInTheModel;
    this.declaredAggregateValues = b.declaredAggregateValues;
    this.declaredTaskParams = b.declaredTaskParams;
    this.implementedExternally = b.implementedExternally;
    this.delivery = b.delivery;

  }

  /**
   * A builder of {@link WorkflowAdapterProperties}, empty except for the values which
   * have a default.
   *
   * @return The builder
   */
  public static WorkflowAdapterProperties.WorkflowAdapterPropertiesBuilder<?, ?> builder() {

    return new WorkflowAdapterProperties.WorkflowAdapterPropertiesBuilderImpl();

  }

  /**
   * The BPMN process id this section is about, taken from the key the application wrote
   * it under.
   *
   * @return The value of {@link #bpmnProcessId}
   */
  public String getBpmnProcessId() {

    return bpmnProcessId;

  }

  /**
   * The workflow module this workflow belongs to - the way back up, which is what lets a
   * message about this workflow name its module.
   *
   * @return The value of {@link #workflowModule}
   */
  public WorkflowModuleAdapterProperties getWorkflowModule() {

    return workflowModule;

  }

  /**
   * The properties of adapters specific to this workflow.
   *
   * @return The value of {@link #adapters}
   */
  public Map<String, AdapterProperties> getAdapters() {

    return adapters;

  }

  /**
   * The properties of the workflow's BPMN tasks.
   *
   * @return The value of {@link #tasks}
   */
  public Map<String, TaskAdapterProperties> getTasks() {

    return tasks;

  }

  /**
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow -
   * what an extension is told about ONE BPMN process, which is the level below the
   * workflow module.
   *
   * @return The value of {@link #extensions}
   */
  public Map<String, Map<String, String>> getExtensions() {

    return extensions;

  }

  /**
   * Whether this workflow may hand its ENTIRE workflow aggregate to the BPMS - the one
   * place this permission may be written, see decision 66 in the repository's
   * DECISIONS.md.
   *
   * @return The value of {@link #allowFullSyncWithBpms}
   */
  public Boolean getAllowFullSyncWithBpms() {

    return allowFullSyncWithBpms;

  }

  /**
   * Whether the expressions of this workflow's BPMN model are meant as they are
   * (<code>accept-expressions-in-the-model</code>) - the most specific of the three
   * levels it may be written at, and the level the startup message hands out.
   *
   * @return The value of {@link #acceptExpressionsInTheModel}
   */
  public Boolean getAcceptExpressionsInTheModel() {

    return acceptExpressionsInTheModel;

  }

  /**
   * The values of the workflow aggregate this workflow declares for the BPMS
   * (<code>declared-aggregate-values</code>).
   *
   * @return The value of {@link #declaredAggregateValues}
   */
  public java.util.List<String> getDeclaredAggregateValues() {

    return declaredAggregateValues;

  }

  /**
   * The <code>&#64;TaskParam</code> parameters of this workflow whose type the developer
   * declared (<code>declared-task-params</code>), by the name the input mapping gives
   * the value.
   *
   * @return The value of {@link #declaredTaskParams}
   */
  public java.util.List<String> getDeclaredTaskParams() {

    return declaredTaskParams;

  }

  /**
   * Whether something other than this application serves every task of this workflow
   * which does not say otherwise (<code>implemented-externally</code>, see
   * {@link MigrationAdapterProperties#implementedExternally}). <code>null</code> means
   * "not configured at this level".
   *
   * @return The value of {@link #implementedExternally}
   */
  public Boolean getImplementedExternally() {

    return implementedExternally;

  }

  /**
   * Overrides <code>vanillabp.delivery</code> for this workflow.
   *
   * @return The value of {@link #delivery}
   */
  public DeliveryProperties getDelivery() {

    return delivery;

  }

  /**
   * The BPMN process id this section is about, taken from the key the application wrote
   * it under.
   *
   * @param bpmnProcessId The value of {@link #bpmnProcessId}
   */
  public void setBpmnProcessId(
      final String bpmnProcessId) {

    this.bpmnProcessId = bpmnProcessId;

  }

  /**
   * The workflow module this workflow belongs to - the way back up, which is what lets a
   * message about this workflow name its module.
   *
   * @param workflowModule The value of {@link #workflowModule}
   */
  public void setWorkflowModule(
      final WorkflowModuleAdapterProperties workflowModule) {

    this.workflowModule = workflowModule;

  }

  /**
   * The properties of adapters specific to this workflow.
   *
   * @param adapters The value of {@link #adapters}
   */
  public void setAdapters(
      final Map<String, AdapterProperties> adapters) {

    this.adapters = adapters;

  }

  /**
   * The properties of the workflow's BPMN tasks.
   *
   * @param tasks The value of {@link #tasks}
   */
  public void setTasks(
      final Map<String, TaskAdapterProperties> tasks) {

    this.tasks = tasks;

  }

  /**
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow -
   * what an extension is told about ONE BPMN process, which is the level below the
   * workflow module.
   *
   * @param extensions The value of {@link #extensions}
   */
  public void setExtensions(
      final Map<String, Map<String, String>> extensions) {

    this.extensions = extensions;

  }

  /**
   * Whether this workflow may hand its ENTIRE workflow aggregate to the BPMS - the one
   * place this permission may be written, see decision 66 in the repository's
   * DECISIONS.md.
   *
   * @param allowFullSyncWithBpms The value of {@link #allowFullSyncWithBpms}
   */
  public void setAllowFullSyncWithBpms(
      final Boolean allowFullSyncWithBpms) {

    this.allowFullSyncWithBpms = allowFullSyncWithBpms;

  }

  /**
   * Whether the expressions of this workflow's BPMN model are meant as they are
   * (<code>accept-expressions-in-the-model</code>) - the most specific of the three
   * levels it may be written at, and the level the startup message hands out.
   *
   * @param acceptExpressionsInTheModel The value of {@link #acceptExpressionsInTheModel}
   */
  public void setAcceptExpressionsInTheModel(
      final Boolean acceptExpressionsInTheModel) {

    this.acceptExpressionsInTheModel = acceptExpressionsInTheModel;

  }

  /**
   * The values of the workflow aggregate this workflow declares for the BPMS
   * (<code>declared-aggregate-values</code>).
   *
   * @param declaredAggregateValues The value of {@link #declaredAggregateValues}
   */
  public void setDeclaredAggregateValues(
      final java.util.List<String> declaredAggregateValues) {

    this.declaredAggregateValues = declaredAggregateValues;

  }

  /**
   * The <code>&#64;TaskParam</code> parameters of this workflow whose type the developer
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
   * Whether something other than this application serves every task of this workflow
   * which does not say otherwise (<code>implemented-externally</code>, see
   * {@link MigrationAdapterProperties#implementedExternally}). <code>null</code> means
   * "not configured at this level".
   *
   * @param implementedExternally The value of {@link #implementedExternally}
   */
  public void setImplementedExternally(
      final Boolean implementedExternally) {

    this.implementedExternally = implementedExternally;

  }

  /**
   * Overrides <code>vanillabp.delivery</code> for this workflow.
   *
   * @param delivery The value of {@link #delivery}
   */
  public void setDelivery(
      final DeliveryProperties delivery) {

    this.delivery = delivery;

  }

}
