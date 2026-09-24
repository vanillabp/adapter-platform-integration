package io.vanillabp.integration.adapter.migration.config;

import java.util.Map;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

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
@Getter
@Setter
@SuperBuilder
public class AdapterProperties {

  /**
   * The empty section a configuration binder starts from: both platforms create the object
   * and then write the keys the application configured into it, one setter per key.
   * <p>
   * It asks the builder for the values, and that is not a detour: Lombok moves the
   * initializer of a field with a default into the builder, so a constructor which sets
   * nothing itself would leave such a field <code>null</code>.
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
   * process or which task is in them.
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
  @Builder.Default
  private Map<String, Map<String, String>> extensions = Map.of();

}
