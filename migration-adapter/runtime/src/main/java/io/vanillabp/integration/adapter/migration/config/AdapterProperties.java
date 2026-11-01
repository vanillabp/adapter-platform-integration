package io.vanillabp.integration.adapter.migration.config;

import java.util.Map;

/**
 * What ONE adapter may be told. The same keys may be written at four levels:
 *
 * <pre>
 * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.tasks.&lt;task&gt;.adapters.&lt;id&gt;.*  (most specific)
 * vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;workflow&gt;.adapters.&lt;id&gt;.*
 * vanillabp.workflow-modules.&lt;module&gt;.adapters.&lt;id&gt;.*
 * vanillabp.adapters.&lt;id&gt;.*                                                                (least specific)
 * </pre>
 *
 * Each of the four binds this class, so each key below may be written at any of them. The
 * <code>&lt;id&gt;</code> is the adapter id. An application which runs two adapters of the
 * same BPMS (the migration case) says different things to each of them by writing two
 * sections.
 * <p>
 * <code>null</code> is the value of every key which nobody wrote. It means "this level says
 * nothing", not "off". The lookup walks from the most specific level to the least specific
 * one, and takes the first value which is not <code>null</code>
 * ({@link MigrationAdapterProperties#resolveForAdapter}). Where no level says anything, the
 * default named at the key applies. Decision 7 in the repository's DECISIONS.md says why an
 * adapter setting may be written in four places.
 * <p>
 * The section of the adapter itself has two more keys than the three levels below it. Those
 * are in {@link AdapterConfigProperties}.
 */
public class AdapterProperties {

  /**
   * The empty section from which a configuration binder starts. Both platforms create the
   * object, and then write the keys which the application configured into it, one setter per
   * key.
   * <p>
   * It asks the builder for the values, and that is not a detour. Every default of this class
   * is on the builder as well as on the field, and both ways into an object end here. A
   * constructor which set the fields itself would have to repeat every default. The first
   * default somebody forgets would leave such a field <code>null</code>.
   */
  public AdapterProperties() {

    this(builder());

  }

  /**
   * Refused here on purpose. The permission to share a whole workflow aggregate belongs to
   * the single workflow (see decision 66 in the repository's DECISIONS.md). It is bound at
   * this level, so that a line written here gets a message which says where it belongs,
   * instead of being ignored.
   */
  private Boolean allowFullSyncWithBpms;

  /**
   * Where to load BPMN files from which are specific to the adapter.
   * <p>
   * It is read at the workflow module and at the adapter, in that order. The global
   * <code>vanillabp.resources-location</code> follows both (see
   * {@link MigrationAdapterProperties#getAdapterResourcesLocationsFor}). The two more
   * specific levels cannot be read. VanillaBP finds the BPMN files before it knows which
   * process or which task is in them. They are bound all the same. So a line written at a
   * workflow or at a task gets a message which names the keys where it belongs, instead of
   * being ignored (see decision 80 in the repository's DECISIONS.md).
   */
  private String resourcesLocation;

  /**
   * How the identifiers of a workflow module are kept apart from those of other workflow
   * modules (see {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance}). It is
   * adapter-scoped, so it can be set per workflow module and workflow. <code>null</code>
   * means "not configured at this level". Then the adapter's own default applies (see
   * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService#defaultNameClashAvoidance()}).
   */
  private io.vanillabp.integration.adapter.spi.NameClashAvoidance nameClashAvoidance;

  /**
   * Whether a task definition is scoped by the BPMN process ID, in addition to the workflow
   * module ID (only relevant for
   * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance#USE_PREFIX}). The default
   * is <code>true</code>. Using one task implementation in several processes is an
   * anti-pattern, so a task definition belongs to its process. Set it to <code>false</code>
   * if an application does that on purpose. <code>null</code> means "not configured at this
   * level".
   */
  private Boolean prefixTaskDefinitionsPerProcess;

  /**
   * Whether VanillaBP remembers the task deliveries of this BPMS. Then a repeated delivery
   * does not run the <code>&#64;WorkflowTask</code> method again, but reports the recorded
   * outcome once more (see {@link io.vanillabp.integration.spi.TaskDeliveryLog}). It is
   * adapter-scoped, so it can be set per workflow module, workflow and task. A single task
   * which does something expensive twice may be treated differently from the rest.
   * <p>
   * The default is <code>true</code>, because not running business code twice is the safer
   * behaviour. It only has an effect where the BPMS may repeat a delivery at all
   * ({@link io.vanillabp.integration.adapter.spi.MigratableProcessService#deliversTasksAtLeastOnce()}),
   * and where the adapter reports a delivery identity. <code>null</code> means "not
   * configured at this level".
   */
  private Boolean deduplicateDeliveries;

  /**
   * Whether something other than this application serves the tasks which this level covers,
   * for this adapter only (<code>implemented-externally</code>, see
   * {@link MigrationAdapterProperties#implementedExternally}). Written below a task, this is
   * how the two adapters of a migration say different things about one task.
   * <code>null</code> means "not configured at this level".
   */
  private Boolean implementedExternally;

  /**
   * The versions of a BPMN process which this application does not serve any more. Each is
   * written in the grammar of the <code>version</code> attribute of
   * <code>&#64;WorkflowTask</code> and its siblings (<code>&lt;4</code>, <code>1-3</code>,
   * <code>v1.0..v2.0</code>, a version tag). The startup check ignores a version which ANY of
   * them covers. So its task definitions need no methods.
   * <p>
   * It is adapter-scoped, so it can be set per workflow module and workflow. Every BPMS counts
   * its own versions. That is why a specification without an adapter would mean nothing, and
   * why the two adapters of a BPMS migration fade out their own versions independently.
   * <code>null</code> or empty means "not configured at this level".
   */
  private java.util.List<String> outfadedVersions;

  /**
   * What happens when workflows still run on a version which is faded out, see
   * {@link OutfadedVersionsInUsePolicy}. The default is
   * {@link OutfadedVersionsInUsePolicy#LOG}. <code>null</code> means "not configured at this
   * level".
   */
  private OutfadedVersionsInUsePolicy outfadedVersionsInUse;

  /**
   * How long a correlated message waits for a workflow which does not wait for it yet
   * (<code>message-time-to-live</code>). It is read at the adapter, the workflow module, the
   * workflow and the single message, and not at a task (see
   * {@link MigrationAdapterProperties#messageTimeToLive}). <code>null</code> means "not
   * configured at this level". What a message without any value gets is up to the adapter,
   * because the BPMS decide that differently.
   */
  private java.time.Duration messageTimeToLive;

  /**
   * What an extension is configured with FOR THIS ADAPTER at this level (properties section
   * <code>...adapters.&lt;id&gt;.extensions.&lt;extension&gt;.*</code>). The keys are the
   * extension ids. The values are what the application wrote below them.
   * <p>
   * An extension is configured on every configured adapter separately. So an application which
   * runs two adapters of the same BPMS type may tell them different things. What an adapter is
   * told wins over what the same level says in general. And a more specific level wins over a
   * less specific one (see decision 7 in the repository's DECISIONS.md and
   * {@link MigrationAdapterProperties#resolveForExtension}).
   */
  private Map<String, Map<String, String>> extensions = Map.of();

  /**
   * The builder of {@link AdapterProperties}. Its two type parameters are the class being
   * built and the builder itself. So a call inherited from a base class comes back as the
   * builder of the subclass, and the next call in the chain sees every key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class AdapterPropertiesBuilder<C extends AdapterProperties, B extends AdapterProperties.AdapterPropertiesBuilder<C, B>> {

    /**
     * Refused here on purpose. The permission to share a whole workflow aggregate belongs to
     * the single workflow (see decision 66 in the repository's DECISIONS.md).
     */
    private Boolean allowFullSyncWithBpms;

    /**
     * Where to load BPMN files from which are specific to the adapter.
     */
    private String resourcesLocation;

    /**
     * How the identifiers of a workflow module are kept apart from those of other
     * workflow modules (see
     * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance}).
     */
    private io.vanillabp.integration.adapter.spi.NameClashAvoidance nameClashAvoidance;

    /**
     * Whether a task definition is scoped by the BPMN process ID, in addition to the workflow
     * module ID (only relevant for
     * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance#USE_PREFIX}).
     */
    private Boolean prefixTaskDefinitionsPerProcess;

    /**
     * Whether VanillaBP remembers the task deliveries of this BPMS. Then a repeated delivery
     * does not run the <code>&#64;WorkflowTask</code> method again, but reports the recorded
     * outcome once more (see {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
     */
    private Boolean deduplicateDeliveries;

    /**
     * Whether something other than this application serves the tasks which this level covers,
     * for this adapter only (<code>implemented-externally</code>, see
     * {@link MigrationAdapterProperties#implementedExternally}). Written below a task, this
     * is how the two adapters of a migration say different things about one task.
     * <code>null</code> means "not configured at this level".
     */
    private Boolean implementedExternally;

    /**
     * The versions of a BPMN process which this application does not serve any more. Each is
     * written in the grammar of the <code>version</code> attribute of
     * <code>&#64;WorkflowTask</code> and its siblings (<code>&lt;4</code>, <code>1-3</code>,
     * <code>v1.0..v2.0</code>, a version tag).
     */
    private java.util.List<String> outfadedVersions;

    /**
     * What happens when workflows still run on a version which is faded out, see
     * {@link OutfadedVersionsInUsePolicy}.
     */
    private OutfadedVersionsInUsePolicy outfadedVersionsInUse;

    /**
     * How long a correlated message waits for a workflow which does not wait for it yet.
     */
    private java.time.Duration messageTimeToLive;

    /**
     * What an extension is configured with FOR THIS ADAPTER at this level (properties section
     * <code>...adapters.&lt;id&gt;.extensions.&lt;extension&gt;.*</code>). The builder starts
     * from the same value as the field.
     */
    private Map<String, Map<String, String>> extensions = Map.of();

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one.
     * {@link AdapterProperties#builder()} hands out the builder of this class.
     */
    public AdapterPropertiesBuilder() {
    }

    /**
     * Refused here on purpose. The permission to share a whole workflow aggregate belongs to
     * the single workflow (see decision 66 in the repository's DECISIONS.md).
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
     * Where to load BPMN files from which are specific to the adapter.
     *
     * @param resourcesLocation The value of {@link #resourcesLocation}
     * @return This builder, so the calls chain
     */
    public B resourcesLocation(
        final String resourcesLocation) {

      this.resourcesLocation = resourcesLocation;
      return self();

    }

    /**
     * How the identifiers of a workflow module are kept apart from those of other
     * workflow modules (see
     * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance}).
     *
     * @param nameClashAvoidance The value of {@link #nameClashAvoidance}
     * @return This builder, so the calls chain
     */
    public B nameClashAvoidance(
        final io.vanillabp.integration.adapter.spi.NameClashAvoidance nameClashAvoidance) {

      this.nameClashAvoidance = nameClashAvoidance;
      return self();

    }

    /**
     * Whether a task definition is scoped by the BPMN process ID, in addition to the workflow
     * module ID (only relevant for
     * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance#USE_PREFIX}).
     *
     * @param prefixTaskDefinitionsPerProcess The value of {@link #prefixTaskDefinitionsPerProcess}
     * @return This builder, so the calls chain
     */
    public B prefixTaskDefinitionsPerProcess(
        final Boolean prefixTaskDefinitionsPerProcess) {

      this.prefixTaskDefinitionsPerProcess = prefixTaskDefinitionsPerProcess;
      return self();

    }

    /**
     * Whether VanillaBP remembers the task deliveries of this BPMS. Then a repeated delivery
     * does not run the <code>&#64;WorkflowTask</code> method again, but reports the recorded
     * outcome once more (see {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
     *
     * @param deduplicateDeliveries The value of {@link #deduplicateDeliveries}
     * @return This builder, so the calls chain
     */
    public B deduplicateDeliveries(
        final Boolean deduplicateDeliveries) {

      this.deduplicateDeliveries = deduplicateDeliveries;
      return self();

    }

    /**
     * Whether something other than this application serves the tasks which this level covers,
     * for this adapter only (<code>implemented-externally</code>, see
     * {@link MigrationAdapterProperties#implementedExternally}). Written below a task, this
     * is how the two adapters of a migration say different things about one task.
     * <code>null</code> means "not configured at this level".
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
     * The versions of a BPMN process which this application does not serve any more. Each is
     * written in the grammar of the <code>version</code> attribute of
     * <code>&#64;WorkflowTask</code> and its siblings (<code>&lt;4</code>, <code>1-3</code>,
     * <code>v1.0..v2.0</code>, a version tag).
     *
     * @param outfadedVersions The value of {@link #outfadedVersions}
     * @return This builder, so the calls chain
     */
    public B outfadedVersions(
        final java.util.List<String> outfadedVersions) {

      this.outfadedVersions = outfadedVersions;
      return self();

    }

    /**
     * What happens when workflows still run on a version which is faded out, see
     * {@link OutfadedVersionsInUsePolicy}.
     *
     * @param outfadedVersionsInUse The value of {@link #outfadedVersionsInUse}
     * @return This builder, so the calls chain
     */
    public B outfadedVersionsInUse(
        final OutfadedVersionsInUsePolicy outfadedVersionsInUse) {

      this.outfadedVersionsInUse = outfadedVersionsInUse;
      return self();

    }

    /**
     * How long a correlated message waits for a workflow which does not wait for it yet.
     *
     * @param messageTimeToLive The time-to-live, <code>null</code> where this level says
     *          nothing
     * @return This builder, so the calls chain
     */
    public B messageTimeToLive(
        final java.time.Duration messageTimeToLive) {

      this.messageTimeToLive = messageTimeToLive;
      return self();

    }

    /**
     * What an extension is configured with FOR THIS ADAPTER at this level (properties
     * section <code>...adapters.&lt;id&gt;.extensions.&lt;extension&gt;.*</code>).
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

      return "AdapterProperties.AdapterPropertiesBuilder("
          + "allowFullSyncWithBpms="
          + allowFullSyncWithBpms
          + ", "
          + "resourcesLocation="
          + resourcesLocation
          + ", "
          + "nameClashAvoidance="
          + nameClashAvoidance
          + ", "
          + "prefixTaskDefinitionsPerProcess="
          + prefixTaskDefinitionsPerProcess
          + ", "
          + "deduplicateDeliveries="
          + deduplicateDeliveries
          + ", "
          + "implementedExternally="
          + implementedExternally
          + ", "
          + "outfadedVersions="
          + outfadedVersions
          + ", "
          + "outfadedVersionsInUse="
          + outfadedVersionsInUse
          + ", "
          + "messageTimeToLive="
          + messageTimeToLive
          + ", "
          + "extensions="
          + extensions
          + ")";

    }

  }

  /**
   * The builder which {@link #builder()} hands out: the one which builds
   * {@link AdapterProperties} itself, and not a subclass of it.
   */
  private static final class AdapterPropertiesBuilderImpl extends AdapterProperties.AdapterPropertiesBuilder<AdapterProperties, AdapterProperties.AdapterPropertiesBuilderImpl> {

    /**
     * Nobody but {@link AdapterProperties#builder()} builds one.
     */
    private AdapterPropertiesBuilderImpl() {
    }

    /**
     * This builder, typed as itself.
     *
     * @return This builder
     */
    @Override
    protected AdapterProperties.AdapterPropertiesBuilderImpl self() {

      return this;

    }

    /**
     * Builds the object from what was written into this builder.
     *
     * @return The built object
     */
    @Override
    public AdapterProperties build() {

      return new AdapterProperties(this);

    }

  }

  /**
   * Every builder of this class and of its subclasses builds through this. It is the one
   * place where the values of this class move from the builder into the object. So a subclass
   * builder fills the keys of its base class as well.
   *
   * @param b The builder holding what was written
   */
  protected AdapterProperties(
      final AdapterProperties.AdapterPropertiesBuilder<?, ?> b) {

    this.allowFullSyncWithBpms = b.allowFullSyncWithBpms;
    this.resourcesLocation = b.resourcesLocation;
    this.nameClashAvoidance = b.nameClashAvoidance;
    this.prefixTaskDefinitionsPerProcess = b.prefixTaskDefinitionsPerProcess;
    this.deduplicateDeliveries = b.deduplicateDeliveries;
    this.implementedExternally = b.implementedExternally;
    this.outfadedVersions = b.outfadedVersions;
    this.outfadedVersionsInUse = b.outfadedVersionsInUse;
    this.messageTimeToLive = b.messageTimeToLive;
    this.extensions = b.extensions;

  }

  /**
   * A builder of {@link AdapterProperties}. It is empty, except for the values which have a
   * default.
   *
   * @return The builder
   */
  public static AdapterProperties.AdapterPropertiesBuilder<?, ?> builder() {

    return new AdapterProperties.AdapterPropertiesBuilderImpl();

  }

  /**
   * Refused here on purpose. The permission to share a whole workflow aggregate belongs to
   * the single workflow (see decision 66 in the repository's DECISIONS.md).
   *
   * @return The value of {@link #allowFullSyncWithBpms}
   */
  public Boolean getAllowFullSyncWithBpms() {

    return allowFullSyncWithBpms;

  }

  /**
   * Where to load BPMN files from which are specific to the adapter.
   *
   * @return The value of {@link #resourcesLocation}
   */
  public String getResourcesLocation() {

    return resourcesLocation;

  }

  /**
   * How the identifiers of a workflow module are kept apart from those of other workflow
   * modules (see {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance}).
   *
   * @return The value of {@link #nameClashAvoidance}
   */
  public io.vanillabp.integration.adapter.spi.NameClashAvoidance getNameClashAvoidance() {

    return nameClashAvoidance;

  }

  /**
   * Whether a task definition is scoped by the BPMN process ID, in addition to the workflow
   * module ID (only relevant for
   * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance#USE_PREFIX}).
   *
   * @return The value of {@link #prefixTaskDefinitionsPerProcess}
   */
  public Boolean getPrefixTaskDefinitionsPerProcess() {

    return prefixTaskDefinitionsPerProcess;

  }

  /**
   * Whether VanillaBP remembers the task deliveries of this BPMS. Then a repeated delivery
   * does not run the <code>&#64;WorkflowTask</code> method again, but reports the recorded
   * outcome once more (see {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
   *
   * @return The value of {@link #deduplicateDeliveries}
   */
  public Boolean getDeduplicateDeliveries() {

    return deduplicateDeliveries;

  }

  /**
   * Whether something other than this application serves the tasks which this level covers,
   * for this adapter only (<code>implemented-externally</code>, see
   * {@link MigrationAdapterProperties#implementedExternally}). Written below a task, this is
   * how the two adapters of a migration say different things about one task.
   * <code>null</code> means "not configured at this level".
   *
   * @return The value of {@link #implementedExternally}
   */
  public Boolean getImplementedExternally() {

    return implementedExternally;

  }

  /**
   * The versions of a BPMN process which this application does not serve any more. Each is
   * written in the grammar of the <code>version</code> attribute of
   * <code>&#64;WorkflowTask</code> and its siblings (<code>&lt;4</code>, <code>1-3</code>,
   * <code>v1.0..v2.0</code>, a version tag).
   *
   * @return The value of {@link #outfadedVersions}
   */
  public java.util.List<String> getOutfadedVersions() {

    return outfadedVersions;

  }

  /**
   * What happens when workflows still run on a version which is faded out, see
   * {@link OutfadedVersionsInUsePolicy}.
   *
   * @return The value of {@link #outfadedVersionsInUse}
   */
  public OutfadedVersionsInUsePolicy getOutfadedVersionsInUse() {

    return outfadedVersionsInUse;

  }

  /**
   * How long a correlated message waits for a workflow which does not wait for it yet.
   *
   * @return The value of {@link #messageTimeToLive}
   */
  public java.time.Duration getMessageTimeToLive() {

    return messageTimeToLive;

  }

  /**
   * What an extension is configured with FOR THIS ADAPTER at this level (properties
   * section <code>...adapters.&lt;id&gt;.extensions.&lt;extension&gt;.*</code>).
   *
   * @return The value of {@link #extensions}
   */
  public Map<String, Map<String, String>> getExtensions() {

    return extensions;

  }

  /**
   * Refused here on purpose. The permission to share a whole workflow aggregate belongs to
   * the single workflow (see decision 66 in the repository's DECISIONS.md).
   *
   * @param allowFullSyncWithBpms The value of {@link #allowFullSyncWithBpms}
   */
  public void setAllowFullSyncWithBpms(
      final Boolean allowFullSyncWithBpms) {

    this.allowFullSyncWithBpms = allowFullSyncWithBpms;

  }

  /**
   * Where to load BPMN files from which are specific to the adapter.
   *
   * @param resourcesLocation The value of {@link #resourcesLocation}
   */
  public void setResourcesLocation(
      final String resourcesLocation) {

    this.resourcesLocation = resourcesLocation;

  }

  /**
   * How the identifiers of a workflow module are kept apart from those of other workflow
   * modules (see {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance}).
   *
   * @param nameClashAvoidance The value of {@link #nameClashAvoidance}
   */
  public void setNameClashAvoidance(
      final io.vanillabp.integration.adapter.spi.NameClashAvoidance nameClashAvoidance) {

    this.nameClashAvoidance = nameClashAvoidance;

  }

  /**
   * Whether a task definition is scoped by the BPMN process ID, in addition to the workflow
   * module ID (only relevant for
   * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance#USE_PREFIX}).
   *
   * @param prefixTaskDefinitionsPerProcess The value of {@link #prefixTaskDefinitionsPerProcess}
   */
  public void setPrefixTaskDefinitionsPerProcess(
      final Boolean prefixTaskDefinitionsPerProcess) {

    this.prefixTaskDefinitionsPerProcess = prefixTaskDefinitionsPerProcess;

  }

  /**
   * Whether VanillaBP remembers the task deliveries of this BPMS. Then a repeated delivery
   * does not run the <code>&#64;WorkflowTask</code> method again, but reports the recorded
   * outcome once more (see {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
   *
   * @param deduplicateDeliveries The value of {@link #deduplicateDeliveries}
   */
  public void setDeduplicateDeliveries(
      final Boolean deduplicateDeliveries) {

    this.deduplicateDeliveries = deduplicateDeliveries;

  }

  /**
   * Whether something other than this application serves the tasks which this level covers,
   * for this adapter only (<code>implemented-externally</code>, see
   * {@link MigrationAdapterProperties#implementedExternally}). Written below a task, this is
   * how the two adapters of a migration say different things about one task.
   * <code>null</code> means "not configured at this level".
   *
   * @param implementedExternally The value of {@link #implementedExternally}
   */
  public void setImplementedExternally(
      final Boolean implementedExternally) {

    this.implementedExternally = implementedExternally;

  }

  /**
   * The versions of a BPMN process which this application does not serve any more. Each is
   * written in the grammar of the <code>version</code> attribute of
   * <code>&#64;WorkflowTask</code> and its siblings (<code>&lt;4</code>, <code>1-3</code>,
   * <code>v1.0..v2.0</code>, a version tag).
   *
   * @param outfadedVersions The value of {@link #outfadedVersions}
   */
  public void setOutfadedVersions(
      final java.util.List<String> outfadedVersions) {

    this.outfadedVersions = outfadedVersions;

  }

  /**
   * What happens when workflows still run on a version which is faded out, see
   * {@link OutfadedVersionsInUsePolicy}.
   *
   * @param outfadedVersionsInUse The value of {@link #outfadedVersionsInUse}
   */
  public void setOutfadedVersionsInUse(
      final OutfadedVersionsInUsePolicy outfadedVersionsInUse) {

    this.outfadedVersionsInUse = outfadedVersionsInUse;

  }

  /**
   * How long a correlated message waits for a workflow which does not wait for it yet.
   *
   * @param messageTimeToLive The value of {@link #messageTimeToLive}
   */
  public void setMessageTimeToLive(
      final java.time.Duration messageTimeToLive) {

    this.messageTimeToLive = messageTimeToLive;

  }

  /**
   * What an extension is configured with FOR THIS ADAPTER at this level (properties
   * section <code>...adapters.&lt;id&gt;.extensions.&lt;extension&gt;.*</code>).
   *
   * @param extensions The value of {@link #extensions}
   */
  public void setExtensions(
      final Map<String, Map<String, String>> extensions) {

    this.extensions = extensions;

  }

}
