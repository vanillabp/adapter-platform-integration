package io.vanillabp.integration.deployment.config;

import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.SmallRyeConfigBuilderCustomizer;

/**
 * Migration adapter properties need some customization of SmallRye config.
 * For details checkout source code of
 * {@link #configBuilder(SmallRyeConfigBuilder)}.
 * <p>
 * Hint: Activated by &quot;META-INF/services/io.smallrye.config.SmallRyeConfigBuilderCustomize/&quot;.
 *
 * @see <a href="https://smallrye.io/smallrye-config/3.12.3/config/customizer/">SmallRye Config Customizer</a>
 */
public class QuarkusMigrationAdapterPropertiesConfigBuilderCustomizer implements SmallRyeConfigBuilderCustomizer {

  /**
   * Adopt builder according to needs of migration adapters.
   * For details checkout source code of
   * {@link SmallRyeConfigBuilderCustomizer#configBuilder(SmallRyeConfigBuilder)}.
   *
   * @param builder SmallRye builder provided by Quarkus
   */
  @Override
  public void configBuilder(
      final SmallRyeConfigBuilder builder) {

    // In Quarkus properties defined by Java interfaces are mandatory. They
    // can be turned into optional values by using an Optional<..> wrapper
    // on a value base. In the case of VanillaBP properties, the sub-sections and
    // properties valid depend on the adapters used. Therefor
    // missing-property-validation has to be turned off for the entire section:
    builder.withMappingIgnore("%s.**".formatted(QuarkusMigrationAdapterProperties.PREFIX));

  }

}
