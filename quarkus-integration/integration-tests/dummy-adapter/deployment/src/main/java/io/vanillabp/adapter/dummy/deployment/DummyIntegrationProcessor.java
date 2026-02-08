package io.vanillabp.adapter.dummy.deployment;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.adapter.dummy.deployment.config.DummyProperties;
import io.vanillabp.adapter.dummy.runtime.MigratableProcessService;
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
   * used by VanillaBP Quarkus integration to determine process service classes
   * to be used by the migration adapter.
   *
   * @param properties Dummy properties
   * @param featureProducer Feature build item producer used to register the dummy adapter extension
   * @return The {@link VanillaBpMigratableProcessServiceBuildItem} build item
   */
  @BuildStep
  VanillaBpMigratableProcessServiceBuildItem buildProcessServices(
      final DummyProperties properties,
      final BuildProducer<FeatureBuildItem> featureProducer) {

    featureProducer.produce(new FeatureBuildItem(FEATURE));

    return VanillaBpMigratableProcessServiceBuildItem
        .builder()
        .adapterType("dummy")
        .migratableProcessServiceBeanClass(MigratableProcessService.class.getName())
        .build();

  }

}
