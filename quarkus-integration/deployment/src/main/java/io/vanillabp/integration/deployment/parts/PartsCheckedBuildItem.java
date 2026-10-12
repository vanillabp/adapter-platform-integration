package io.vanillabp.integration.deployment.parts;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * Says that the VanillaBP parts of this application were checked against each other. The
 * deployment pipeline waits for it, and this keeps the check in the build. Quarkus drops a
 * step nobody waits for, instead of running it.
 *
 * @see PartVersionsBuildStepProcessor
 */
public final class PartsCheckedBuildItem extends SimpleBuildItem {

  /**
   * Built by {@link PartVersionsBuildStepProcessor#checkPartsBelongTogether} after it
   * checked the VanillaBP parts of this application. A pair which does not belong together
   * ends the build in that step. So the item only exists where the parts may run together.
   * A pair which nobody can judge only warns, see decision 71 in the repository's
   * DECISIONS.md.
   */
  public PartsCheckedBuildItem() {
  }

}
