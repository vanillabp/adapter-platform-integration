package io.vanillabp.adapter.dummy.deployment;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.adapter.dummy.runtime.DummyDeploymentServiceProducer;
import io.vanillabp.adapter.dummy.runtime.DummyProcessServiceProducer;
import io.vanillabp.integration.deployment.pipeline.VanillaBpAdapterDeploymentServiceBuildItem;
import io.vanillabp.integration.deployment.processservice.VanillaBpMigratableProcessServiceBuildItem;
import lombok.extern.slf4j.Slf4j;

/**
 * A dummy adapter Quarkus extension used by VanillaBP integration Quarkus
 * extension for tests.
 */
@Slf4j
class DummyIntegrationProcessor {

  private static final String FEATURE = "vanillabp-dummy";

  /**
   * Builds the {@link VanillaBpMigratableProcessServiceBuildItem} build item
   * used by VanillaBP Quarkus integration to determine and register the
   * process-service bean of the adapter - the VanillaBP extension registers the
   * announced bean class, no separate self-registration is needed.
   *
   * @param featureProducer Feature build item producer used to register the dummy adapter extension
   * @return The {@link VanillaBpMigratableProcessServiceBuildItem} build item
   */
  @BuildStep
  VanillaBpMigratableProcessServiceBuildItem buildProcessServices(
      final BuildProducer<FeatureBuildItem> featureProducer) {

    featureProducer.produce(new FeatureBuildItem(FEATURE));

    return VanillaBpMigratableProcessServiceBuildItem
        .builder()
        .adapterType("dummy")
        .migratableProcessServiceBeanClass(DummyProcessServiceProducer.class.getName())
        .build();

  }

  /**
   * Builds the {@link VanillaBpAdapterDeploymentServiceBuildItem} build item used
   * by the VanillaBP Quarkus integration to determine and register the
   * deployment-service bean of the adapter (consumed by the runtime deployment
   * pipeline) - the VanillaBP extension registers the announced bean class, no
   * separate self-registration is needed.
   *
   * @return The {@link VanillaBpAdapterDeploymentServiceBuildItem} build item
   */
  @BuildStep
  VanillaBpAdapterDeploymentServiceBuildItem buildDeploymentServices() {

    return VanillaBpAdapterDeploymentServiceBuildItem
        .builder()
        .adapterType("dummy")
        .deploymentServiceBeanClass(DummyDeploymentServiceProducer.class.getName())
        .build();

  }

}
