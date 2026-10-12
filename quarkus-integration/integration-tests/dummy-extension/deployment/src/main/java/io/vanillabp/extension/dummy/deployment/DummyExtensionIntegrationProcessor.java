package io.vanillabp.extension.dummy.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.extension.dummy.runtime.DummyExtensionProducer;

/**
 * A dummy VanillaBP extension, packaged as a Quarkus extension. It contributes an
 * {@code ExtensionWiringService}, like the VanillaBP Business Cockpit does. The Quarkus
 * tests of the VanillaBP integration use it. Unlike adapters, extensions announce no build
 * item. They simply register the bean which produces their {@code ExtensionWiringService}
 * element bean(s). The platform keeps beans of that type from being removed by ArC as
 * unused.
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
