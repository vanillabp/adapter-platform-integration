package io.vanillabp.extension.dummy.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.extension.dummy.runtime.DummyExtensionProducer;

/**
 * A dummy VanillaBP extension (an {@code ExtensionWiringService} contributor, e.g.
 * like the VanillaBP Business Cockpit) packaged as a Quarkus extension - used by the
 * VanillaBP integration's Quarkus tests. Unlike adapters, extensions announce no
 * build item: they simply register the bean producing their
 * {@code ExtensionWiringService} element bean(s) - the platform keeps beans of that
 * type from ArC's unused-bean removal.
 */
class DummyExtensionIntegrationProcessor {

  private static final String FEATURE = "vanillabp-dummy-extension";

  /**
   * Registers the extension feature and the producer of the dummy extension's
   * wiring service.
   *
   * @param featureProducer Feature build item producer
   * @return The bean registration build item
   */
  @BuildStep
  AdditionalBeanBuildItem registerWiringServiceProducer(
      final BuildProducer<FeatureBuildItem> featureProducer) {

    featureProducer.produce(new FeatureBuildItem(FEATURE));

    return AdditionalBeanBuildItem
        .builder()
        .addBeanClass(DummyExtensionProducer.class)
        .setUnremovable()
        .build();

  }

}
