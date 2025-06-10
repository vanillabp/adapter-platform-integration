package io.vanillabp.integration.deployment.config;

import io.quarkus.runtime.configuration.ConfigBuilder;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Migration adapter properties need some customization of SmallRye config.
 * Checkout source code of {@link #configBuilder(SmallRyeConfigBuilder)}
 * for details.
 */
public class MigrationAdapterPropertiesBuilder implements ConfigBuilder {

  /**
   * Adopt builder according to needs of migration adapters.
   * Checkout source code of {@link #configBuilder(SmallRyeConfigBuilder)}
   * for details.
   * 
   * @param builder SmallRye builder provided by Quarkus
   * @return Adapted builder.
   */
  @Override
  public SmallRyeConfigBuilder configBuilder(
      final SmallRyeConfigBuilder builder) {

    // In Quarkus properties defined by Java interfaces are mandatory. They
    // can be turned into optional values by using an Optional<..> wrapper
    // on a value base. In the case of VanillaBP properties, the sub-sections and
    // properties valid depend on the adapters used. Therefor
    // missing-property-validation has to be turned of for all
    return builder.withMappingIgnore("vanillabp.**");

  }

}
