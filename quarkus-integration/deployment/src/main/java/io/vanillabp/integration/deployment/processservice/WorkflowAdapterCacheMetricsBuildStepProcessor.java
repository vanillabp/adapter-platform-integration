package io.vanillabp.integration.deployment.processservice;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.vanillabp.integration.runtime.processservice.WorkflowAdapterCacheMetricsProducer;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes the election cache's numbers as Micrometer meters - but only for an
 * application which uses Micrometer. VanillaBP does not bring it: metrics are the
 * application's decision, and an application without them has to boot unchanged.
 * <p>
 * The signal is the Micrometer extension's build-step class on the DEPLOYMENT
 * classpath, present exactly when the application depends on the extension. The
 * presence of Micrometer's own classes would not do - they can be dragged in
 * transitively without the extension, and then nothing produces the
 * {@code MeterRegistry} bean our producer needs.
 */
@Slf4j
public class WorkflowAdapterCacheMetricsBuildStepProcessor {

  private static final String MICROMETER_EXTENSION_PROCESSOR = "io.quarkus.micrometer.deployment.MicrometerProcessor";

  /**
   * @param additionalBeans Producer used to register the meter binder's producer
   */
  @BuildStep
  void registerWorkflowAdapterCacheMeters(
      final BuildProducer<AdditionalBeanBuildItem> additionalBeans) {

    if (!isMicrometerExtensionPresent()) {
      return;
    }

    log.debug(
        "Micrometer found: the election cache reports its statistics as meters '{}.*'",
        io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics.METER_PREFIX);

    additionalBeans
        .produce(AdditionalBeanBuildItem
            .builder()
            .addBeanClass(WorkflowAdapterCacheMetricsProducer.class)
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
