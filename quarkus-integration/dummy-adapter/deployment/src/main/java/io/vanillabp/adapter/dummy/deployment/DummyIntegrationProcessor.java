package io.vanillabp.adapter.dummy.deployment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.adapter.dummy.deployment.config.DummyProperties;

class DummyIntegrationProcessor {

  private static Logger log = LoggerFactory.getLogger(DummyIntegrationProcessor.class);

  private static final String FEATURE = "vanillabp-dummy";

  @BuildStep
  void buildProcessServices(
      final DummyProperties properties,
      final BuildProducer<FeatureBuildItem> featureProducer) {

    featureProducer.produce(new FeatureBuildItem(FEATURE));

    log.info("Dummy-Props: {}", properties.defaultAdapter());

  }

}
