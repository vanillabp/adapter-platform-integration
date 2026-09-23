package io.vanillabp.integration.deployment.pipeline;

import io.quarkus.builder.item.MultiBuildItem;
import lombok.Builder;
import lombok.Getter;

/**
 * The {@link io.quarkus.builder.item.BuildItem} adapters have to produce to announce
 * their {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService} beans
 * to the VanillaBP Quarkus integration - the deployment-pipeline counterpart of
 * {@link io.vanillabp.integration.deployment.processservice.VanillaBpMigratableProcessServiceBuildItem}.
 * <p>
 * Hint: It is a {@link MultiBuildItem} because multiple adapters may be used at the
 * same time (e.g., for migration).
 */
@Builder
@Getter
public final class VanillaBpAdapterDeploymentServiceBuildItem extends MultiBuildItem {

  /**
   * The name of the adapter's type
   */
  private String adapterType;

  /**
   * The CDI bean class providing the adapter's
   * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService} instances
   * (usually an <code>@ApplicationScoped</code> class with an
   * <code>@Produces</code> method yielding ONE bean of type
   * <code>List&lt;AdapterDeploymentService&lt;Object, Object&gt;&gt;</code> with
   * one instance per configured adapter id). It is registered as an unremovable
   * additional bean by the VanillaBP extension - adapters need no separate
   * self-registration. May be null if the adapter registers its beans itself.
   */
  private String deploymentServiceBeanClass;

  /**
   * Built by the adapter's own Quarkus extension through the builder, one item per adapter
   * type. {@link DeploymentPipelineBuildStepProcessor#buildAdapterDeploymentServiceBeans}
   * reads it and registers the bean class named in it.
   * <p>
   * The constructor is not part of the adapter-facing contract, the builder is: an adapter
   * lives in a package of its own and reaches this class through
   * <code>VanillaBpAdapterDeploymentServiceBuildItem.builder()</code>.
   *
   * @param adapterType The type of the adapter announcing itself, e.g.
   *          <code>camunda8</code>
   * @param deploymentServiceBeanClass The bean class described above, or <code>null</code>
   */
  VanillaBpAdapterDeploymentServiceBuildItem(
      final String adapterType,
      final String deploymentServiceBeanClass) {

    this.adapterType = adapterType;
    this.deploymentServiceBeanClass = deploymentServiceBeanClass;

  }

}
