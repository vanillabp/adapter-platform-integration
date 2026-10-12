package io.vanillabp.integration.deployment.pipeline;

import io.quarkus.builder.item.MultiBuildItem;
import lombok.Builder;
import lombok.Getter;

/**
 * The {@link io.quarkus.builder.item.BuildItem} which adapters produce to announce their
 * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService} beans to the
 * VanillaBP Quarkus integration. It is the deployment-pipeline counterpart of
 * {@link io.vanillabp.integration.deployment.processservice.VanillaBpMigratableProcessServiceBuildItem}.
 * <p>
 * It is a {@link MultiBuildItem}, because several adapters may be used at the same time
 * (e.g. for a migration).
 */
@Builder
@Getter
public final class VanillaBpAdapterDeploymentServiceBuildItem extends MultiBuildItem {

  /**
   * The name of the adapter's type
   */
  private String adapterType;

  /**
   * The CDI bean class which provides the adapter's
   * {@link io.vanillabp.integration.adapter.spi.AdapterDeploymentService} instances.
   * Usually it is an <code>@ApplicationScoped</code> class with a <code>@Produces</code>
   * method. That method returns ONE bean of type
   * <code>List&lt;AdapterDeploymentService&lt;Object, Object&gt;&gt;</code>, with one
   * instance for each configured adapter id. The VanillaBP extension registers the class as
   * an unremovable additional bean, so the adapter does not need to register it itself. May
   * be null if the adapter registers its beans itself.
   */
  private String deploymentServiceBeanClass;

  /**
   * Built by the adapter's own Quarkus extension through the builder, one item for each
   * adapter type. {@link DeploymentPipelineBuildStepProcessor#buildAdapterDeploymentServiceBeans}
   * reads it and registers the bean class it names.
   * <p>
   * The builder is part of the contract for adapters, the constructor is not. An adapter lives
   * in a package of its own. It reaches this class through
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
