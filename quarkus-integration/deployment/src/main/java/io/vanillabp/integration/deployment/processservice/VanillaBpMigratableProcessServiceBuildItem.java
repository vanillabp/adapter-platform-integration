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
   * The CDI bean class providing the adapter's
   * {@link io.vanillabp.integration.adapter.spi.MigratableProcessService} (usually
   * an <code>@ApplicationScoped</code> producer). It is registered as an
   * unremovable additional bean by the VanillaBP extension - adapters need no
   * separate self-registration. May be null if the adapter registers its beans
   * itself.
   */
  private String migratableProcessServiceBeanClass;

}
