package io.vanillabp.integration.deployment.processservice;

import io.quarkus.builder.item.MultiBuildItem;
import lombok.Builder;
import lombok.Getter;

/**
 * The {@link io.quarkus.builder.item.BuildItem}, adapters have to produce
 * to be processed by VanillaBP Quarkus integration.
 * <p>
 * Hint: It is a {@link MultiBuildItem} because multiple adapters may be used
 * at the same time (e.g., for migration).
 */
@Builder
@Getter
public final class VanillaBpMigratableProcessServiceBuildItem extends MultiBuildItem {

  /**
   * The name of the adapter's type
   */
  private String adapterType;

  /**
   * The adapter's bean class to be used for bean instantiation
   */
  private String migratableProcessServiceBeanClass;

}
