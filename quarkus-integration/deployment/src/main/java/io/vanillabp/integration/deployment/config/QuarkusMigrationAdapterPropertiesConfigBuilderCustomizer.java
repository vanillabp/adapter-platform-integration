package io.vanillabp.integration.deployment.config;


import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.SmallRyeConfigBuilderCustomizer;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;

/**
 * This class is used by Quarkus {@link io.quarkus.deployment.configuration.BuildTimeConfigurationReader},
 * responsible for reading application properties at build time for build time analyses.
 * <p>
 * Migration adapter properties need some customization of SmallRye config.
 * Since configuration is processed in augmentation phase the
 * {@link io.quarkus.deployment.builditem.StaticInitConfigBuilderBuildItem}
 * is not suitable for the job (see also
 * <a href="https://stackoverflow.com/questions/79660219/classcast-exception-in-custom-extension-having-pure-static-properties-levaraging"
 * target="_blank">Stackoverflow</a>).
 * <p>
 * What exactly is adapted can be read in the source code of
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
    // on a value base. In the case of VanillaBP properties, the subsections and
    // properties valid depend on the adapters used. Therefor
    // missing-property-validation has to be turned off for the entire section:
    builder.withMappingIgnore("%s.**".formatted(QuarkusMigrationAdapterProperties.PREFIX));

  }

}
