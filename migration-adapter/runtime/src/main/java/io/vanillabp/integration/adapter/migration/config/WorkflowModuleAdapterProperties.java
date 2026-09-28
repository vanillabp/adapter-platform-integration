package io.vanillabp.integration.adapter.migration.config;

import java.util.Map;

/**
 * What an application says about ONE workflow module (properties section
 * <code>vanillabp.workflow-modules.&lt;module&gt;.*</code>, the key being the module id
 * from its <code>META-INF/workflow-module</code> descriptor).
 * <p>
 * It is the second of the four levels an adapter setting may be written at, below the
 * adapter's own section and above the single workflow (decision 7 in the repository's
 * DECISIONS.md). Next to those adapter keys it may say for its own workflows what the
 * global sections say for the whole application: which adapters serve, how transactions,
 * the election and the delivery records are treated, and what an extension is told.
 * <p>
 * A module which configures nothing still gets a section: the classpath facts derive one
 * per module found there (decision 8 in the repository's DECISIONS.md).
 */
public class WorkflowModuleAdapterProperties extends AdaptersConfigurationProperties {

  /**
   * The workflow module this section is about, taken from the key the application wrote it
   * under. Written by {@link MigrationAdapterProperties#validateAndLink()} rather than by
   * the binder, so a section which was built by hand carries it only after that call.
   */
  String workflowModuleId;

  /**
   * What the adapters are told for this workflow module. Keys are the adapter ids, and
   * what may stand below one of them is {@link AdapterProperties} - the same keys as at
   * the three other levels.
   */
  private Map<String, AdapterProperties> adapters = Map.of();

  /**
   * The empty section a configuration binder starts from, one per configured workflow
   * module: both platforms create the object and then write the keys the application
   * configured into it, one setter per key.
   * <p>
   * It asks the builder for the values, and that is not a detour: every default of this
   * class stands on the builder as well as on the field, and both ways into an object
   * end here. A constructor which set the fields itself would have to repeat every
   * default, and the first one somebody forgets would leave the maps of this section
   * <code>null</code> instead of empty.
   */
  public WorkflowModuleAdapterProperties() {

    this(builder());

  }

  /**
   * The workflows of the workflow module. The key is the BPMN process ID.
   * <p>
   * <i>Hint:</i> Back-references (BPMN process ID, workflow module) are linked by
   * {@link MigrationAdapterProperties#validateAndLink()}.
   */
  private Map<String, WorkflowAdapterProperties> workflows = Map.of();

  /**
   * Refused here on purpose: the permission to share a whole workflow aggregate belongs
   * to the single workflow (see decision 66 in the repository's DECISIONS.md). It is
   * bound at this level so that a line written here is answered with a message saying
   * where it belongs, instead of being ignored.
   */
  private Boolean allowFullSyncWithBpms;

  /**
   * Whether the expressions of this module's BPMN models are meant as they are
   * (<code>accept-expressions-in-the-model</code>). A workflow of the module which says
   * nothing keeps this answer.
   */
  private Boolean acceptExpressionsInTheModel;

  /**
   * The <code>&#64;TaskParam</code> parameters of this workflow module whose type the
   * developer declared (<code>declared-task-params</code>). The second least specific of
   * the four levels; a task, and then a workflow, outranks it.
   */
  private java.util.List<String> declaredTaskParams;

  /**
   * Overrides <code>vanillabp.transactions</code> for this workflow module. A setting
   * left undefined here means the global one applies, so a single module can accept
   * unguarded writes while every other one keeps failing the startup check.
   */
  private TransactionsProperties transactions;

  /**
   * Overrides <code>vanillabp.election</code> for this workflow module. A setting
   * left out here means "whatever is configured globally".
   */
  private ElectionProperties election;

  /**
   * Overrides <code>vanillabp.delivery</code> for this workflow module. A setting left
   * undefined here means the global one applies, so one module can release the records of
   * its ended workflows while another keeps them for support.
   */
  private DeliveryProperties delivery;

  /**
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow
   * module - the place an extension configured once for the whole application says
   * something different about one of its modules (the Business Cockpit's URI of a module,
   * say). Keys are the extension ids.
   */
  private Map<String, Map<String, String>> extensions = Map.of();

  /**
   * The builder of {@link WorkflowModuleAdapterProperties}. Its two type parameters
   * carry the class being built and the builder itself, so a call inherited from a base
   * class comes back as the builder of the subclass and the next call in the chain sees
   * every key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class WorkflowModuleAdapterPropertiesBuilder<C extends WorkflowModuleAdapterProperties, B extends WorkflowModuleAdapterProperties.WorkflowModuleAdapterPropertiesBuilder<C, B>> extends AdaptersConfigurationProperties.AdaptersConfigurationPropertiesBuilder<C, B> {

    /**
     * The workflow module this section is about, taken from the key the application
     * wrote it under.
     */
    private String workflowModuleId;

    /**
     * What the adapters are told for this workflow module. The builder starts from the
     * same value the field does.
     */
    private Map<String, AdapterProperties> adapters = Map.of();

    /**
     * The workflows of the workflow module. The builder starts from the same value the
     * field does.
     */
    private Map<String, WorkflowAdapterProperties> workflows = Map.of();

    /**
     * Refused here on purpose: the permission to share a whole workflow aggregate
     * belongs to the single workflow (see decision 66 in the repository's DECISIONS.md).
     */
    private Boolean allowFullSyncWithBpms;

    /**
     * Whether the expressions of this module's BPMN models are meant as they are
     * (<code>accept-expressions-in-the-model</code>). A workflow of the module which says
     * nothing keeps this answer.
     */
    private Boolean acceptExpressionsInTheModel;

    /**
     * The <code>&#64;TaskParam</code> parameters of this workflow module whose type the
     * developer declared (<code>declared-task-params</code>).
     */
    private java.util.List<String> declaredTaskParams;

    /**
     * Overrides <code>vanillabp.transactions</code> for this workflow module.
     */
    private TransactionsProperties transactions;

    /**
     * Overrides <code>vanillabp.election</code> for this workflow module.
     */
    private ElectionProperties election;

    /**
     * Overrides <code>vanillabp.delivery</code> for this workflow module.
     */
    private DeliveryProperties delivery;

    /**
     * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow
     * module - the place an extension configured once for the whole application says
     * something different about one of its modules (the Business Cockpit's URI of a
     * module, say). The builder starts from the same value the field does.
     */
    private Map<String, Map<String, String>> extensions = Map.of();

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one:
     * {@link WorkflowModuleAdapterProperties#builder()} hands out the builder of this
     * class.
     */
    public WorkflowModuleAdapterPropertiesBuilder() {
    }

    /**
     * The workflow module this section is about, taken from the key the application
     * wrote it under.
     *
     * @param workflowModuleId The value of {@link #workflowModuleId}
     * @return This builder, so the calls chain
     */
    public B workflowModuleId(
        final String workflowModuleId) {

      this.workflowModuleId = workflowModuleId;
      return self();

    }

    /**
     * What the adapters are told for this workflow module.
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
     * The workflows of the workflow module.
     *
     * @param workflows The value of {@link #workflows}
     * @return This builder, so the calls chain
     */
    public B workflows(
        final Map<String, WorkflowAdapterProperties> workflows) {

      this.workflows = workflows;
      return self();

    }

    /**
     * Refused here on purpose: the permission to share a whole workflow aggregate
     * belongs to the single workflow (see decision 66 in the repository's DECISIONS.md).
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
     * Whether the expressions of this module's BPMN models are meant as they are
     * (<code>accept-expressions-in-the-model</code>). A workflow of the module which says
     * nothing keeps this answer.
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
     * The <code>&#64;TaskParam</code> parameters of this workflow module whose type the
     * developer declared (<code>declared-task-params</code>).
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
     * Overrides <code>vanillabp.transactions</code> for this workflow module.
     *
     * @param transactions The value of {@link #transactions}
     * @return This builder, so the calls chain
     */
    public B transactions(
        final TransactionsProperties transactions) {

      this.transactions = transactions;
      return self();

    }

    /**
     * Overrides <code>vanillabp.election</code> for this workflow module.
     *
     * @param election The value of {@link #election}
     * @return This builder, so the calls chain
     */
    public B election(
        final ElectionProperties election) {

      this.election = election;
      return self();

    }

    /**
     * Overrides <code>vanillabp.delivery</code> for this workflow module.
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
     * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow
     * module - the place an extension configured once for the whole application says
     * something different about one of its modules (the Business Cockpit's URI of a
     * module, say).
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

      return "WorkflowModuleAdapterProperties.WorkflowModuleAdapterPropertiesBuilder("
          + "super="
          + super.toString()
          + ", "
          + "workflowModuleId="
          + workflowModuleId
          + ", "
          + "adapters="
          + adapters
          + ", "
          + "workflows="
          + workflows
          + ", "
          + "allowFullSyncWithBpms="
          + allowFullSyncWithBpms
          + ", "
          + "acceptExpressionsInTheModel="
          + acceptExpressionsInTheModel
          + ", "
          + "declaredTaskParams="
          + declaredTaskParams
          + ", "
          + "transactions="
          + transactions
          + ", "
          + "election="
          + election
          + ", "
          + "delivery="
          + delivery
          + ", "
          + "extensions="
          + extensions
          + ")";

    }

  }

  /**
   * The builder {@link #builder()} hands out: the one which builds
   * {@link WorkflowModuleAdapterProperties} itself rather than a subclass of it.
   */
  private static final class WorkflowModuleAdapterPropertiesBuilderImpl extends WorkflowModuleAdapterProperties.WorkflowModuleAdapterPropertiesBuilder<WorkflowModuleAdapterProperties, WorkflowModuleAdapterProperties.WorkflowModuleAdapterPropertiesBuilderImpl> {

    /**
     * Nobody but {@link WorkflowModuleAdapterProperties#builder()} builds one.
     */
    private WorkflowModuleAdapterPropertiesBuilderImpl() {
    }

    /**
     * This builder, typed as itself.
     *
     * @return This builder
     */
    @Override
    protected WorkflowModuleAdapterProperties.WorkflowModuleAdapterPropertiesBuilderImpl self() {

      return this;

    }

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public WorkflowModuleAdapterProperties build() {

      return new WorkflowModuleAdapterProperties(this);

    }

  }

  /**
   * What every builder of this class and of its subclasses builds through. It is the one
   * place the values of this class move from the builder into the object, so a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected WorkflowModuleAdapterProperties(
      final WorkflowModuleAdapterProperties.WorkflowModuleAdapterPropertiesBuilder<?, ?> b) {

    super(b);

    this.workflowModuleId = b.workflowModuleId;
    this.adapters = b.adapters;
    this.workflows = b.workflows;
    this.allowFullSyncWithBpms = b.allowFullSyncWithBpms;
    this.acceptExpressionsInTheModel = b.acceptExpressionsInTheModel;
    this.declaredTaskParams = b.declaredTaskParams;
    this.transactions = b.transactions;
    this.election = b.election;
    this.delivery = b.delivery;
    this.extensions = b.extensions;

  }

  /**
   * A builder of {@link WorkflowModuleAdapterProperties}, empty except for the values
   * which have a default.
   *
   * @return The builder
   */
  public static WorkflowModuleAdapterProperties.WorkflowModuleAdapterPropertiesBuilder<?, ?> builder() {

    return new WorkflowModuleAdapterProperties.WorkflowModuleAdapterPropertiesBuilderImpl();

  }

  /**
   * The workflow module this section is about, taken from the key the application wrote
   * it under.
   *
   * @return The value of {@link #workflowModuleId}
   */
  public String getWorkflowModuleId() {

    return workflowModuleId;

  }

  /**
   * What the adapters are told for this workflow module.
   *
   * @return The value of {@link #adapters}
   */
  public Map<String, AdapterProperties> getAdapters() {

    return adapters;

  }

  /**
   * The workflows of the workflow module.
   *
   * @return The value of {@link #workflows}
   */
  public Map<String, WorkflowAdapterProperties> getWorkflows() {

    return workflows;

  }

  /**
   * Refused here on purpose: the permission to share a whole workflow aggregate belongs
   * to the single workflow (see decision 66 in the repository's DECISIONS.md).
   *
   * @return The value of {@link #allowFullSyncWithBpms}
   */
  public Boolean getAllowFullSyncWithBpms() {

    return allowFullSyncWithBpms;

  }

  /**
   * Whether the expressions of this module's BPMN models are meant as they are
   * (<code>accept-expressions-in-the-model</code>). A workflow of the module which says
   * nothing keeps this answer.
   *
   * @return The value of {@link #acceptExpressionsInTheModel}
   */
  public Boolean getAcceptExpressionsInTheModel() {

    return acceptExpressionsInTheModel;

  }

  /**
   * The <code>&#64;TaskParam</code> parameters of this workflow module whose type the
   * developer declared (<code>declared-task-params</code>).
   *
   * @return The value of {@link #declaredTaskParams}
   */
  public java.util.List<String> getDeclaredTaskParams() {

    return declaredTaskParams;

  }

  /**
   * Overrides <code>vanillabp.transactions</code> for this workflow module.
   *
   * @return The value of {@link #transactions}
   */
  public TransactionsProperties getTransactions() {

    return transactions;

  }

  /**
   * Overrides <code>vanillabp.election</code> for this workflow module.
   *
   * @return The value of {@link #election}
   */
  public ElectionProperties getElection() {

    return election;

  }

  /**
   * Overrides <code>vanillabp.delivery</code> for this workflow module.
   *
   * @return The value of {@link #delivery}
   */
  public DeliveryProperties getDelivery() {

    return delivery;

  }

  /**
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow
   * module - the place an extension configured once for the whole application says
   * something different about one of its modules (the Business Cockpit's URI of a
   * module, say).
   *
   * @return The value of {@link #extensions}
   */
  public Map<String, Map<String, String>> getExtensions() {

    return extensions;

  }

  /**
   * The workflow module this section is about, taken from the key the application wrote
   * it under.
   *
   * @param workflowModuleId The value of {@link #workflowModuleId}
   */
  public void setWorkflowModuleId(
      final String workflowModuleId) {

    this.workflowModuleId = workflowModuleId;

  }

  /**
   * What the adapters are told for this workflow module.
   *
   * @param adapters The value of {@link #adapters}
   */
  public void setAdapters(
      final Map<String, AdapterProperties> adapters) {

    this.adapters = adapters;

  }

  /**
   * The workflows of the workflow module.
   *
   * @param workflows The value of {@link #workflows}
   */
  public void setWorkflows(
      final Map<String, WorkflowAdapterProperties> workflows) {

    this.workflows = workflows;

  }

  /**
   * Refused here on purpose: the permission to share a whole workflow aggregate belongs
   * to the single workflow (see decision 66 in the repository's DECISIONS.md).
   *
   * @param allowFullSyncWithBpms The value of {@link #allowFullSyncWithBpms}
   */
  public void setAllowFullSyncWithBpms(
      final Boolean allowFullSyncWithBpms) {

    this.allowFullSyncWithBpms = allowFullSyncWithBpms;

  }

  /**
   * Whether the expressions of this module's BPMN models are meant as they are
   * (<code>accept-expressions-in-the-model</code>). A workflow of the module which says
   * nothing keeps this answer.
   *
   * @param acceptExpressionsInTheModel The value of {@link #acceptExpressionsInTheModel}
   */
  public void setAcceptExpressionsInTheModel(
      final Boolean acceptExpressionsInTheModel) {

    this.acceptExpressionsInTheModel = acceptExpressionsInTheModel;

  }

  /**
   * The <code>&#64;TaskParam</code> parameters of this workflow module whose type the
   * developer declared (<code>declared-task-params</code>).
   *
   * @param declaredTaskParams The value of {@link #declaredTaskParams}
   */
  public void setDeclaredTaskParams(
      final java.util.List<String> declaredTaskParams) {

    this.declaredTaskParams = declaredTaskParams;

  }

  /**
   * Overrides <code>vanillabp.transactions</code> for this workflow module.
   *
   * @param transactions The value of {@link #transactions}
   */
  public void setTransactions(
      final TransactionsProperties transactions) {

    this.transactions = transactions;

  }

  /**
   * Overrides <code>vanillabp.election</code> for this workflow module.
   *
   * @param election The value of {@link #election}
   */
  public void setElection(
      final ElectionProperties election) {

    this.election = election;

  }

  /**
   * Overrides <code>vanillabp.delivery</code> for this workflow module.
   *
   * @param delivery The value of {@link #delivery}
   */
  public void setDelivery(
      final DeliveryProperties delivery) {

    this.delivery = delivery;

  }

  /**
   * Overrides <code>vanillabp.extensions.&lt;extension&gt;.*</code> for this workflow
   * module - the place an extension configured once for the whole application says
   * something different about one of its modules (the Business Cockpit's URI of a
   * module, say).
   *
   * @param extensions The value of {@link #extensions}
   */
  public void setExtensions(
      final Map<String, Map<String, String>> extensions) {

    this.extensions = extensions;

  }

}
