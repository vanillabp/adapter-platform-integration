package io.vanillabp.integration.deployment.processservice;

import io.quarkus.builder.item.MultiBuildItem;
import lombok.Builder;
import lombok.Getter;

/**
 * The {@link io.quarkus.builder.item.BuildItem} adapter have to produce
 * in order to be processed by VanillaBP Quarkus integration.
 * <p>
 * Hint: It is a {@link MultiBuildItem} because multiple adapters may be used
 * at the same time (e.g. for migration).
 */
@Builder
@Getter
public final class VanillaBpMigratableProcessServiceBuildItem extends MultiBuildItem {

  /**
   * The adapter's name
   */
  private String adapterName;

  /**
   * The adapter's bean class to be used for bean instantiation
   */
  private String migratableProcessServiceBeanClass;

}
