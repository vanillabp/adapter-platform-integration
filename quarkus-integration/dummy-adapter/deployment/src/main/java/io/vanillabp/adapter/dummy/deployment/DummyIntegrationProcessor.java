package io.vanillabp.adapter.dummy.deployment;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.adapter.dummy.deployment.config.DummyProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * A dummy adapter Quarkus extension used by VanillaBP integration Quarkus
 * extension for tests.
 */
@Slf4j
class DummyIntegrationProcessor {

  private static final String FEATURE = "vanillabp-dummy";

  @BuildStep
  void buildProcessServices(
      final DummyProperties properties,
      final BuildProducer<FeatureBuildItem> featureProducer) {

    featureProducer.produce(new FeatureBuildItem(FEATURE));

  }

}
