package io.vanillabp.integration.runtime.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;

import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.smallrye.config.EnvConfigSource;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.smallrye.config.source.yaml.YamlConfigSource;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A section of the <code>vanillabp</code> tree written as <code>{}</code> in YAML, while the
 * keys of the same id come from another source. On Spring Boot the empty map became a
 * property with an empty value, and the binding then skipped the keys below it. Nothing was
 * reported, so the rule "a key VanillaBP does not read ends the startup" did not hold.
 * <p>
 * This test asks the same of SmallRye Config, the way a Quarkus application binds. SmallRye
 * finds the keys of a map by their names, so the keys from the other source must arrive. And
 * the empty map must not leave a name behind which no mapping knows. Otherwise the check for
 * unknown keys would end the startup, because of a line which is valid YAML.
 * <p>
 * Each case is tried in both orders: the empty section in the stronger source, and in the
 * weaker one. The keys come from system properties, from a profile and from an environment
 * variable. A typo below the empty section must still end the startup, so the check for
 * unknown keys is proven to be on in this binding.
 */
@ExtendWith(SuppressOutputExtension.class)
public class QuarkusEmptySectionKeepsTheKeysTest {

  private static final int APPLICATION_YAML = 255;

  private static final int ENVIRONMENT_VARIABLES = 300;

  private static final int SYSTEM_PROPERTIES = 400;

  private static final Map<String, String> KEYS_OF_THE_ID = Map.of(
      "vanillabp.adapters.saas.type", "camunda8",
      "vanillabp.adapters.saas.deployment-failure", "warn",
      "vanillabp.workflow-modules.loans.adapters.saas.resources-location", "classpath*:/loans/saas");

  /**
   * Binds the platform's mapping the way Quarkus does at runtime. SmallRye checks for
   * unknown keys below the prefix of a mapping by default. So a name which no mapping knows
   * makes {@link SmallRyeConfigBuilder#build()} throw.
   */
  private static QuarkusMigrationAdapterProperties bound(
      final String profile,
      final ConfigSource... sources) {

    final var builder = new SmallRyeConfigBuilder()
        .withMapping(QuarkusMigrationAdapterProperties.class)
        .withSources(sources);
    if (profile != null) {
      builder.withProfile(profile);
    }
    final SmallRyeConfig config = builder.build();
    return config.getConfigMapping(QuarkusMigrationAdapterProperties.class);

  }

  private static YamlConfigSource yaml(
      final String name,
      final int ordinal,
      final String content) {

    return new YamlConfigSource(name, content, ordinal);

  }

  private static void assertTheKeysArrived(
      final QuarkusMigrationAdapterProperties properties) {

    final var adapter = properties.adapters().get("saas");
    assertEquals(Optional.of("camunda8"), adapter.type());
    assertEquals(Optional.of(DeploymentFailurePolicy.WARN), adapter.deploymentFailure());
    assertEquals(
        Optional.of("classpath*:/loans/saas"),
        properties.workflowModules().get("loans").adapters().get("saas").resourcesLocation());

  }

  @Test
  @DisplayName("An empty adapter section in application.yaml keeps the keys from system properties")
  public void anEmptyAdapterSectionKeepsTheStrongerKeys() {

    assertTheKeysArrived(bound(
        null,
        yaml("application.yaml", APPLICATION_YAML, """
            vanillabp:
              adapters:
                saas: {}
              workflow-modules:
                loans:
                  adapters:
                    saas: {}
            """),
        new PropertiesConfigSource(KEYS_OF_THE_ID, "system properties", SYSTEM_PROPERTIES)));

  }

  @Test
  @DisplayName("An empty adapter section in a stronger source keeps the keys of application.yaml")
  public void anEmptyAdapterSectionKeepsTheWeakerKeys() {

    assertTheKeysArrived(bound(
        null,
        yaml("application.yaml", APPLICATION_YAML, """
            vanillabp:
              adapters:
                saas:
                  type: camunda8
                  deployment-failure: warn
              workflow-modules:
                loans:
                  adapters:
                    saas:
                      resources-location: classpath*:/loans/saas
            """),
        yaml("stronger.yaml", SYSTEM_PROPERTIES, """
            vanillabp:
              adapters:
                saas: {}
              workflow-modules:
                loans:
                  adapters:
                    saas: {}
            """)));

  }

  @Test
  @DisplayName("An empty adapter section of the active profile keeps the keys written without profile")
  public void anEmptySectionOfTheProfileKeepsTheKeys() {

    assertTheKeysArrived(bound(
        "tenant",
        yaml("application.yaml", APPLICATION_YAML, """
            vanillabp:
              adapters:
                saas:
                  type: camunda8
                  deployment-failure: warn
              workflow-modules:
                loans:
                  adapters:
                    saas:
                      resources-location: classpath*:/loans/saas
            "%tenant":
              vanillabp:
                adapters:
                  saas: {}
                workflow-modules:
                  loans:
                    adapters:
                      saas: {}
            """)));

  }

  @Test
  @DisplayName("The keys of the active profile arrive below an empty adapter section written without profile")
  public void theKeysOfTheProfileArriveBelowAnEmptySection() {

    assertTheKeysArrived(bound(
        "tenant",
        yaml("application.yaml", APPLICATION_YAML, """
            vanillabp:
              adapters:
                saas: {}
              workflow-modules:
                loans:
                  adapters:
                    saas: {}
            "%tenant":
              vanillabp:
                adapters:
                  saas:
                    type: camunda8
                    deployment-failure: warn
                workflow-modules:
                  loans:
                    adapters:
                      saas:
                        resources-location: classpath*:/loans/saas
            """)));

  }

  @Test
  @DisplayName("An empty adapter section in application.yaml keeps the type from an environment variable")
  public void anEmptyAdapterSectionKeepsTheEnvironmentVariable() {

    final var properties = bound(
        null,
        yaml("application.yaml", APPLICATION_YAML, """
            vanillabp:
              adapters:
                saas: {}
            """),
        new EnvConfigSource(Map.of("VANILLABP_ADAPTERS_SAAS_TYPE", "camunda8"), ENVIRONMENT_VARIABLES));

    assertEquals(Optional.of("camunda8"), properties.adapters().get("saas").type());

  }

  @Test
  @DisplayName("A typo below an empty adapter section still ends the startup")
  public void aTypoBelowAnEmptySectionStillEndsTheStartup() {

    final var failure = assertThrows(
        RuntimeException.class,
        () -> bound(
            null,
            yaml("application.yaml", APPLICATION_YAML, """
                vanillabp:
                  adapters:
                    saas: {}
                """),
            new PropertiesConfigSource(
                Map.of("vanillabp.adapters.saas.deployment-failur", "warn"), "system properties", SYSTEM_PROPERTIES)));

    assertTrue(
        failure.getMessage().contains("vanillabp.adapters.saas.deployment-failur"),
        failure.getMessage());

  }

  @Test
  @DisplayName("An empty adapter section alone adds no id, which the note for Quarkus in the startup message explains")
  public void anEmptySectionAloneLeavesNoName() {

    final var properties = bound(
        null,
        yaml("application.yaml", APPLICATION_YAML, """
            vanillabp:
              adapters:
                saas: {}
            """));

    assertFalse(properties.adapters().containsKey("saas"));

  }

}
