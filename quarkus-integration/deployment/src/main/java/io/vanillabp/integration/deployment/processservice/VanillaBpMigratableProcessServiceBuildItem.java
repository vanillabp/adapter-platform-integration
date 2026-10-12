package io.vanillabp.integration.deployment.processservice;

import io.quarkus.builder.item.MultiBuildItem;
import lombok.Builder;
import lombok.Getter;

/**
 * The {@link io.quarkus.builder.item.BuildItem} which adapters produce, so the VanillaBP
 * Quarkus integration processes them.
 * <p>
 * It is a {@link MultiBuildItem}, because several adapters may be used at the same time
 * (e.g. for a migration).
 */
@Builder
@Getter
public final class VanillaBpMigratableProcessServiceBuildItem extends MultiBuildItem {

  /**
   * The name of the adapter's type
   */
  private String adapterType;

  /**
   * The CDI bean class which provides the adapter's
   * {@link io.vanillabp.integration.adapter.spi.MigratableProcessService}. Usually it is
   * an <code>@ApplicationScoped</code> producer. The VanillaBP extension registers it as an
   * unremovable additional bean, so the adapter does not need to register it itself. May be
   * null if the adapter registers its beans itself.
   */
  private String migratableProcessServiceBeanClass;

  /**
   * Built by the adapter's own Quarkus extension through the builder, one item for each
   * adapter type. {@code VanillaBpBuildStepProcessor} reads it and registers the bean class
   * it names. {@code ConfigBuildStepProcessor} reads which adapter types this application
   * ships. The configuration is checked against this list.
   * <p>
   * The builder is part of the contract for adapters, the constructor is not. An adapter
   * lives in a package of its own. It reaches this class through
   * <code>VanillaBpMigratableProcessServiceBuildItem.builder()</code>.
   *
   * @param adapterType The type of the adapter announcing itself, e.g.
   *          <code>camunda8</code>
   * @param migratableProcessServiceBeanClass The bean class described above, or
   *          <code>null</code>
   */
  VanillaBpMigratableProcessServiceBuildItem(
      final String adapterType,
      final String migratableProcessServiceBeanClass) {

    this.adapterType = adapterType;
    this.migratableProcessServiceBeanClass = migratableProcessServiceBeanClass;

  }

}
