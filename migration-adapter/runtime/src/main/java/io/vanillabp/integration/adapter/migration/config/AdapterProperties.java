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

}
