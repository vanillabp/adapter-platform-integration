package io.vanillabp.integration.adapter.migration.config;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

/**
 * Configuration of what VanillaBP does when an adapter cannot locate workflows at all
 * (properties section <code>vanillabp.election</code>, overridable per workflow module
 * as <code>vanillabp.workflow-modules.&lt;id&gt;.election</code>).
 * <p>
 * Locating the BPMS which holds a workflow is a walk over the prioritized adapters, and
 * it is exactly as right as the answers it gets. An adapter which cannot ask its BPMS -
 * a Camunda 8 cluster without secondary storage, the Process-Engine-API, which has no
 * query API at all - answers optimistically, which is correct while it is the only BPMS
 * configured and a guess as soon as it is not. VanillaBP refuses to boot such a
 * combination, and an application which wants it anyway says so here.
 */
@Getter
@Setter
@NoArgsConstructor
@SuperBuilder
public class ElectionProperties {

  /**
   * What VanillaBP does about a prioritized adapter which cannot locate workflows,
   * next to at least one other adapter.
   */
  public enum GuessingAdapters {

    /**
     * The boot ends with a guiding message naming the adapter and the fix. The
     * default.
     */
    REJECTED,

    /**
     * The application accepts that operations on existing workflows are routed by
     * list order rather than by an answer. The message is logged as a WARN instead of
     * ending the boot.
     */
    ACCEPTED

  }

  /**
   * Whether an adapter which has to guess is accepted next to others.
   * <code>null</code> in a workflow module's section means "whatever is configured
   * globally"; the default of the global section is {@link GuessingAdapters#REJECTED}.
   */
  private GuessingAdapters guessingAdapters;

}
