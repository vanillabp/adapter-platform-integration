package io.vanillabp.integration.adapter.migration.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class AdapterProperties {

  /**
   * Where to load BPMN files from, which are specific to the adapter
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

}
