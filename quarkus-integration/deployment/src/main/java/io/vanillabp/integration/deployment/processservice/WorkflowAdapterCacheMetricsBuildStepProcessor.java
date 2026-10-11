package io.vanillabp.integration.deployment.processservice;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.vanillabp.integration.runtime.processservice.WorkflowAdapterCacheMetricsProducer;
import lombok.extern.slf4j.Slf4j;

/**
 * Publishes the numbers of the election cache as Micrometer meters, but only for an
 * application which uses Micrometer. VanillaBP does not bring Micrometer. Metrics are the
 * application's decision, and an application without them has to boot unchanged.
 * <p>
 * The signal is the Micrometer extension's build-step class on the DEPLOYMENT classpath.
 * It is there exactly when the application depends on the extension. Micrometer's own
 * classes are not enough as a signal. Another dependency can pull them in without the
 * extension. Then nothing produces the {@code MeterRegistry} bean which our producer needs.
 */
@Slf4j
public class WorkflowAdapterCacheMetricsBuildStepProcessor {

  private static final String MICROMETER_EXTENSION_PROCESSOR = "io.quarkus.micrometer.deployment.MicrometerProcessor";

  /**
   * Quarkus builds this processor while it augments the application and calls the build step
   * below on it. Nothing else builds it, and the step keeps no state in it.
   */
  public WorkflowAdapterCacheMetricsBuildStepProcessor() {
  }

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
        "Micrometer found: the election reports its numbers as meters '{}.*', and the in-memory cache "
            + "reports what it knows about itself as '{}.*' (a cache which is not the in-memory one "
            + "publishes its own under a prefix of its own)",
        io.vanillabp.integration.adapter.migration.processservice.WorkflowAdapterCacheStatistics.METER_PREFIX,
        io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCacheStatistics.METER_PREFIX);

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
