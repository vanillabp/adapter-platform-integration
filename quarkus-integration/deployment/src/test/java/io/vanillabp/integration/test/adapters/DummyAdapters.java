package io.vanillabp.integration.test.adapters;

import java.util.function.Consumer;

import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.builder.BuildContext;
import io.quarkus.builder.BuildStep;
import io.quarkus.deployment.builditem.CapabilityBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.vanillabp.integration.deployment.processservice.VanillaBpMigratableProcessServiceBuildItem;

public class DummyAdapters {

  public static Consumer<BuildChainBuilder> singleDummyAdapter() {
    return builder -> builder.addBuildStep(new BuildStep() {
      @Override
      public void execute(
          final BuildContext context) {
        context.produce(new FeatureBuildItem("vanillabp-dummy"));
        context.produce(new CapabilityBuildItem("io.vanillabp.adapter.dummy", "vanillabp-dummy"));
        context.produce(VanillaBpMigratableProcessServiceBuildItem
            .builder()
            .adapterName("dummy")
            .build());
      }
    })
        .produces(VanillaBpMigratableProcessServiceBuildItem.class)
        .produces(CapabilityBuildItem.class)
        .produces(FeatureBuildItem.class)
        .build();
  }

}
