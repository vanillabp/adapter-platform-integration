package io.vanillabp.integration.deployment.parts;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * Says that the VanillaBP parts of this application were judged against each other. The
 * deployment pipeline waits for it, which is what keeps the check in the build: a step
 * nobody waits for is dropped instead of being run.
 *
 * @see PartVersionsBuildStepProcessor
 */
public final class PartsCheckedBuildItem extends SimpleBuildItem {

  /**
   * Built by {@link PartVersionsBuildStepProcessor#checkPartsBelongTogether} after it
   * judged the VanillaBP parts of this application. A pair which does not belong together
   * ends the build in that step, so the item exists only where the parts may run together
   * (a pair nobody can judge only warns, see decision 71 in the repository's DECISIONS.md).
   */
  public PartsCheckedBuildItem() {
  }

}
