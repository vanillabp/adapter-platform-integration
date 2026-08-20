package io.vanillabp.integration.deployment.processservice;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.vanillabp.integration.runtime.processservice.VanillaBpMetricsProducer;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes what VanillaBP counts about its deliveries and its outbox as Micrometer
 * meters - but only for an application which uses Micrometer. VanillaBP does not
 * bring it: metrics are the application's decision, and an application without them
 * has to boot unchanged.
 * <p>
 * The signal is the Micrometer extension's build-step class on the DEPLOYMENT
 * classpath, exactly as the election cache's meters do it (see
 * {@code WorkflowAdapterCacheMetricsBuildStepProcessor} for why the presence of
 * Micrometer's own classes would not be enough).
 */
@Slf4j
public class VanillaBpMetricsBuildStepProcessor {

  private static final String MICROMETER_EXTENSION_PROCESSOR = "io.quarkus.micrometer.deployment.MicrometerProcessor";

  /**
   * @param additionalBeans Producer used to register the metrics' producer
   */
  @BuildStep
  void registerVanillaBpMetrics(
      final BuildProducer<AdditionalBeanBuildItem> additionalBeans) {

    if (!isMicrometerExtensionPresent()) {
      return;
    }

    log.debug(
        "Micrometer found: task deliveries and the phase-two outbox report meters '{}.*' resp. '{}.*'",
        io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.TASK_DELIVERIES,
        io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.OUTBOX_DISPATCHES);

    additionalBeans
        .produce(AdditionalBeanBuildItem
            .builder()
            .addBeanClass(VanillaBpMetricsProducer.class)
            .setUnremovable() // applied by Micrometer's own startup, never injected
            .build());

  }

  private static boolean isMicrometerExtensionPresent() {

    try {
      Class.forName(
          MICROMETER_EXTENSION_PROCESSOR,
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
