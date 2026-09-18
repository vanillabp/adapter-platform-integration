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
}
