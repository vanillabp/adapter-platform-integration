package io.vanillabp.integration.deployment.health;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.vanillabp.integration.runtime.health.VanillaBpReadinessCheck;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes the adapters' health as a readiness check - but only for an application
 * which uses the SmallRye Health extension. VanillaBP does not bring it: a health
 * endpoint is the application's decision, and an application without one has to boot
 * unchanged.
 * <p>
 * The signal is the extension's build-step class on the DEPLOYMENT classpath, the
 * same way the Micrometer extension is detected for the meters.
 */
@Slf4j
public class VanillaBpHealthBuildStepProcessor {

  private static final String HEALTH_EXTENSION_PROCESSOR = "io.quarkus.smallrye.health.deployment.SmallRyeHealthProcessor";

  /**
   * Quarkus builds this processor while it augments the application and calls the build step
   * below on it. Nothing else builds it, and the step keeps no state in it.
   */
  public VanillaBpHealthBuildStepProcessor() {
  }

  /**
   * Registers the readiness check, and only where SmallRye Health is part of the
   * application. The question is answered here and not at startup because Quarkus fixes the
   * set of beans while it builds: a check registered in an application without the health
   * extension would be a bean referring to classes which are not there.
   *
   * @param additionalBeans Producer used to register the readiness check
   */
  @BuildStep
  void registerAdapterHealthCheck(
      final BuildProducer<AdditionalBeanBuildItem> additionalBeans) {

    if (!isHealthExtensionPresent()) {
      return;
    }

    log.debug("SmallRye Health found: the BPMS adapters report the readiness check 'vanillabp'");

    additionalBeans
        .produce(AdditionalBeanBuildItem
            .builder()
            .addBeanClass(VanillaBpReadinessCheck.class)
            .setUnremovable() // collected by SmallRye Health, never injected
            .build());

  }

  private static boolean isHealthExtensionPresent() {

    try {
      Class.forName(
          HEALTH_EXTENSION_PROCESSOR,
          false,
          Thread
              .currentThread()
              .getContextClassLoader());
      return true;
    } catch (final ClassNotFoundException | LinkageError e) {
      return false;
    }

  }

}
