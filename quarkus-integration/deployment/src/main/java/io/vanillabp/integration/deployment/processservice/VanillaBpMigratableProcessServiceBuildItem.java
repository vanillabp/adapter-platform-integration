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

  /**
   * Built by the adapter's own Quarkus extension through the builder, one item per adapter
   * type. {@code VanillaBpBuildStepProcessor} reads it and registers the bean class named in
   * it, and {@code ConfigBuildStepProcessor} reads which adapter types this application
   * ships - that is the list the configuration is judged against.
   * <p>
   * The constructor is not part of the adapter-facing contract, the builder is: an adapter
   * lives in a package of its own and reaches this class through
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
