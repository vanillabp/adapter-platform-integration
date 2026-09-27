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
 * Every one of the four binds this class, so each key below may be written at any of them.
 * The <code>&lt;id&gt;</code> is the adapter id, and an application running two adapters of
 * the same BPMS - the migration case - says different things to each of them by writing two
 * sections.
 * <p>
 * <code>null</code> is the value of every key nobody wrote, and it means "this level says
 * nothing" rather than "off": the lookup walks from the most specific level to the least
 * specific one and takes the first value which is not <code>null</code>
 * ({@link MigrationAdapterProperties#resolveForAdapter}). Where no level says anything, the
 * default named at the key applies. Why an adapter setting may be written in four places is
 * decision 7 in the repository's DECISIONS.md.
 * <p>
 * The section of the adapter itself carries two keys more than the three levels below it,
 * and those are in {@link AdapterConfigProperties}.
 */
public class AdapterProperties {

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
  public AdapterProperties() {

    this(builder());

  }

  /**
   * Refused here on purpose: the permission to share a whole workflow aggregate belongs
   * to the single workflow (see decision 66 in the repository's DECISIONS.md). It is
   * bound at this level so that a line written here is answered with a message saying
   * where it belongs, instead of being ignored.
   */
  private Boolean allowFullSyncWithBpms;

  /**
   * Where to load BPMN files from, which are specific to the adapter.
   * <p>
   * Read at the workflow module and at the adapter, in that order, and the global
   * <code>vanillabp.resources-location</code> follows both (see
   * {@link MigrationAdapterProperties#getAdapterResourcesLocationsFor}). The two more
   * specific levels cannot be read: VanillaBP finds the BPMN files before it knows which
   * process or which task is in them. They are bound all the same, so that a line
   * written at a workflow or at a task is answered with a message naming the keys it
   * belongs at, instead of being ignored (see decision 80 in the repository's
   * DECISIONS.md).
   */
  private String resourcesLocation;

  /**
   * How the identifiers of a workflow module are kept apart from those of other
   * workflow modules (see
   * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance}). Adapter-scoped
   * and therefore resolvable per workflow module and workflow; <code>null</code>
   * means "not configured at this level" (the adapter's own default applies then, see
   * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService#defaultNameClashAvoidance()}).
   */
  private io.vanillabp.integration.adapter.spi.NameClashAvoidance nameClashAvoidance;

  /**
   * Whether a task definition is scoped by the BPMN process ID in addition to the
   * workflow module ID (only relevant for
   * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance#USE_PREFIX}).
   * Defaults to <code>true</code>: reusing one task implementation across processes
   * is an anti-pattern, so a task definition belongs to its process. Set it to
   * <code>false</code> if an application does it deliberately. <code>null</code>
   * means "not configured at this level".
   */
  private Boolean prefixTaskDefinitionsPerProcess;

  /**
   * Whether VanillaBP remembers the task deliveries of this BPMS, so a repeated
   * delivery does not run the <code>&#64;WorkflowTask</code> method again but reports
   * the recorded outcome once more (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog}). Adapter-scoped and
   * therefore resolvable per workflow module, workflow and task - a single task doing
   * something expensive twice may be treated differently from the rest.
   * <p>
   * Defaults to <code>true</code>: not running business code twice is the safer
   * behaviour. It has an effect only where the BPMS may repeat a delivery at all
   * ({@link io.vanillabp.integration.adapter.spi.MigratableProcessService#deliversTasksAtLeastOnce()})
   * and the adapter reports a delivery identity. <code>null</code> means "not
   * configured at this level".
   */
  private Boolean deduplicateDeliveries;

  /**
   * The versions of a BPMN process this application does not serve any more, each
   * written in the grammar of the <code>version</code> attribute of
   * <code>&#64;WorkflowTask</code> and its siblings (<code>&lt;4</code>,
   * <code>1-3</code>, <code>v1.0..v2.0</code>, a version tag). A version covered by
   * ANY of them is ignored by the startup check, so its task definitions need no
   * methods.
   * <p>
   * Adapter-scoped and therefore resolvable per workflow module and workflow - every
   * BPMS counts its own versions, which is why a specification without an adapter
   * would be meaningless and why the two adapters of a BPMS migration fade out their
   * own versions independently. <code>null</code> or empty means "not configured at
   * this level".
   */
  private java.util.List<String> outfadedVersions;

  /**
   * What happens when workflows still run on an outfaded version - see
   * {@link OutfadedVersionsInUsePolicy}. Defaults to
   * {@link OutfadedVersionsInUsePolicy#LOG}; <code>null</code> means "not configured
   * at this level".
   */
  private OutfadedVersionsInUsePolicy outfadedVersionsInUse;

  /**
   * What an extension is configured with FOR THIS ADAPTER at this level (properties
   * section <code>...adapters.&lt;id&gt;.extensions.&lt;extension&gt;.*</code>). Keys are
   * the extension ids, values what the application wrote below them.
   * <p>
   * An extension hangs on every configured adapter separately, so an application running
   * two adapters of the same BPMS type may tell them different things. What an adapter is
   * told beats what the same level says in general, and a more specific level beats a less
   * specific one (see decision 53 in the repository's DECISIONS.md and
   * {@link MigrationAdapterProperties#resolveForExtension}).
   */
  private Map<String, Map<String, String>> extensions = Map.of();

  /**
   * The builder of {@link AdapterProperties}. Its two type parameters carry the class
   * being built and the builder itself, so a call inherited from a base class comes back
   * as the builder of the subclass and the next call in the chain sees every key again.
   *
   * @param <C> The class this builder builds
   * @param <B> The builder itself, which every method of the chain returns
   */
  public abstract static class AdapterPropertiesBuilder<C extends AdapterProperties, B extends AdapterProperties.AdapterPropertiesBuilder<C, B>> {

    /**
     * Refused here on purpose: the permission to share a whole workflow aggregate
     * belongs to the single workflow (see decision 66 in the repository's DECISIONS.md).
     */
    private Boolean allowFullSyncWithBpms;

    /**
     * Where to load BPMN files from, which are specific to the adapter.
     */
    private String resourcesLocation;

    /**
     * How the identifiers of a workflow module are kept apart from those of other
     * workflow modules (see
     * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance}).
     */
    private io.vanillabp.integration.adapter.spi.NameClashAvoidance nameClashAvoidance;

    /**
     * Whether a task definition is scoped by the BPMN process ID in addition to the
     * workflow module ID (only relevant for
     * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance#USE_PREFIX}).
     */
    private Boolean prefixTaskDefinitionsPerProcess;

    /**
     * Whether VanillaBP remembers the task deliveries of this BPMS, so a repeated
     * delivery does not run the <code>&#64;WorkflowTask</code> method again but reports
     * the recorded outcome once more (see
     * {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
     */
    private Boolean deduplicateDeliveries;

    /**
     * The versions of a BPMN process this application does not serve any more, each
     * written in the grammar of the <code>version</code> attribute of
     * <code>&#64;WorkflowTask</code> and its siblings (<code>&lt;4</code>,
     * <code>1-3</code>, <code>v1.0..v2.0</code>, a version tag).
     */
    private java.util.List<String> outfadedVersions;

    /**
     * What happens when workflows still run on an outfaded version - see
     * {@link OutfadedVersionsInUsePolicy}.
     */
    private OutfadedVersionsInUsePolicy outfadedVersionsInUse;

    /**
     * What an extension is configured with FOR THIS ADAPTER at this level (properties
     * section <code>...adapters.&lt;id&gt;.extensions.&lt;extension&gt;.*</code>). The
     * builder starts from the same value the field does.
     */
    private Map<String, Map<String, String>> extensions = Map.of();

    /**
     * The builder of a subclass calls this while it is built. Nobody else needs one:
     * {@link AdapterProperties#builder()} hands out the builder of this class.
     */
    public AdapterPropertiesBuilder() {
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
     * Where to load BPMN files from, which are specific to the adapter.
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
     * Whether a task definition is scoped by the BPMN process ID in addition to the
     * workflow module ID (only relevant for
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
     * Whether VanillaBP remembers the task deliveries of this BPMS, so a repeated
     * delivery does not run the <code>&#64;WorkflowTask</code> method again but reports
     * the recorded outcome once more (see
     * {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
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
     * The versions of a BPMN process this application does not serve any more, each
     * written in the grammar of the <code>version</code> attribute of
     * <code>&#64;WorkflowTask</code> and its siblings (<code>&lt;4</code>,
     * <code>1-3</code>, <code>v1.0..v2.0</code>, a version tag).
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
     * What happens when workflows still run on an outfaded version - see
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
          + "outfadedVersions="
          + outfadedVersions
          + ", "
          + "outfadedVersionsInUse="
          + outfadedVersionsInUse
          + ", "
          + "extensions="
          + extensions
          + ")";

    }

  }

  /**
   * The builder {@link #builder()} hands out: the one which builds
   * {@link AdapterProperties} itself rather than a subclass of it.
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
   * What every builder of this class and of its subclasses builds through. It is the one
   * place the values of this class move from the builder into the object, so a subclass
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
    this.outfadedVersions = b.outfadedVersions;
    this.outfadedVersionsInUse = b.outfadedVersionsInUse;
    this.extensions = b.extensions;

  }

  /**
   * A builder of {@link AdapterProperties}, empty except for the values which have a
   * default.
   *
   * @return The builder
   */
  public static AdapterProperties.AdapterPropertiesBuilder<?, ?> builder() {

    return new AdapterProperties.AdapterPropertiesBuilderImpl();

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
   * Where to load BPMN files from, which are specific to the adapter.
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
   * Whether a task definition is scoped by the BPMN process ID in addition to the
   * workflow module ID (only relevant for
   * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance#USE_PREFIX}).
   *
   * @return The value of {@link #prefixTaskDefinitionsPerProcess}
   */
  public Boolean getPrefixTaskDefinitionsPerProcess() {

    return prefixTaskDefinitionsPerProcess;

  }

  /**
   * Whether VanillaBP remembers the task deliveries of this BPMS, so a repeated delivery
   * does not run the <code>&#64;WorkflowTask</code> method again but reports the
   * recorded outcome once more (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
   *
   * @return The value of {@link #deduplicateDeliveries}
   */
  public Boolean getDeduplicateDeliveries() {

    return deduplicateDeliveries;

  }

  /**
   * The versions of a BPMN process this application does not serve any more, each
   * written in the grammar of the <code>version</code> attribute of
   * <code>&#64;WorkflowTask</code> and its siblings (<code>&lt;4</code>,
   * <code>1-3</code>, <code>v1.0..v2.0</code>, a version tag).
   *
   * @return The value of {@link #outfadedVersions}
   */
  public java.util.List<String> getOutfadedVersions() {

    return outfadedVersions;

  }

  /**
   * What happens when workflows still run on an outfaded version - see
   * {@link OutfadedVersionsInUsePolicy}.
   *
   * @return The value of {@link #outfadedVersionsInUse}
   */
  public OutfadedVersionsInUsePolicy getOutfadedVersionsInUse() {

    return outfadedVersionsInUse;

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
   * Where to load BPMN files from, which are specific to the adapter.
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
   * Whether a task definition is scoped by the BPMN process ID in addition to the
   * workflow module ID (only relevant for
   * {@link io.vanillabp.integration.adapter.spi.NameClashAvoidance#USE_PREFIX}).
   *
   * @param prefixTaskDefinitionsPerProcess The value of {@link #prefixTaskDefinitionsPerProcess}
   */
  public void setPrefixTaskDefinitionsPerProcess(
      final Boolean prefixTaskDefinitionsPerProcess) {

    this.prefixTaskDefinitionsPerProcess = prefixTaskDefinitionsPerProcess;

  }

  /**
   * Whether VanillaBP remembers the task deliveries of this BPMS, so a repeated delivery
   * does not run the <code>&#64;WorkflowTask</code> method again but reports the
   * recorded outcome once more (see
   * {@link io.vanillabp.integration.spi.TaskDeliveryLog}).
   *
   * @param deduplicateDeliveries The value of {@link #deduplicateDeliveries}
   */
  public void setDeduplicateDeliveries(
      final Boolean deduplicateDeliveries) {

    this.deduplicateDeliveries = deduplicateDeliveries;

  }

  /**
   * The versions of a BPMN process this application does not serve any more, each
   * written in the grammar of the <code>version</code> attribute of
   * <code>&#64;WorkflowTask</code> and its siblings (<code>&lt;4</code>,
   * <code>1-3</code>, <code>v1.0..v2.0</code>, a version tag).
   *
   * @param outfadedVersions The value of {@link #outfadedVersions}
   */
  public void setOutfadedVersions(
      final java.util.List<String> outfadedVersions) {

    this.outfadedVersions = outfadedVersions;

  }

  /**
   * What happens when workflows still run on an outfaded version - see
   * {@link OutfadedVersionsInUsePolicy}.
   *
   * @param outfadedVersionsInUse The value of {@link #outfadedVersionsInUse}
   */
  public void setOutfadedVersionsInUse(
      final OutfadedVersionsInUsePolicy outfadedVersionsInUse) {

    this.outfadedVersionsInUse = outfadedVersionsInUse;

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
