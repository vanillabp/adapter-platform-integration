package io.vanillabp.integration.runtime.processservice.config;

import io.quarkus.runtime.configuration.ConfigBuilder;
import io.smallrye.config.SmallRyeConfigBuilder;

/**
 * Migration adapter properties need some customization of SmallRye config.
 * For details checkout source code of
 * {@link #configBuilder(SmallRyeConfigBuilder)}.
 */
public class QuarkusMigrationAdapterPropertiesBuilder implements ConfigBuilder {

  /**
   * Adopt builder according to needs of migration adapters.
   * For details checkout source code of
   * {@link ConfigBuilder#configBuilder(SmallRyeConfigBuilder)}.
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
    // missing-property-validation has to be turned off for the entire section:
    return builder.withMappingIgnore("vanillabp.**");

  }

}
