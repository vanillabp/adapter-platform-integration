package io.vanillabp.integration.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

import io.vanillabp.integration.adapter.AdapterBeanRegistrarSupport;
import io.vanillabp.integration.adapter.migration.config.DeploymentFailurePolicy;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.config.EmptySections;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A section of the <code>vanillabp</code> tree written as <code>{}</code> in a YAML file keeps
 * the keys which other property sources set below it. Spring Boot reads the empty map as an
 * empty value at the section's own name, and that value used to hide every key below it. This
 * holds for an adapter section, for the adapter section of a workflow module, for the whole
 * tree, and for the overlay class of an adapter. See {@link EmptySections}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AnEmptySectionKeepsTheKeysTest {

  private static final String SECTIONS = "empty-sections/sections.yaml";

  private static final String[] COMMAND_LINE = {
      "--vanillabp.adapters.saas.deployment-failure=warn", "--vanillabp.adapters.saas.rest-address=http://saas:26500", "--vanillabp.workflow-modules.test-module.adapters.saas.resources-location=classpath:from-the-command-line"
  };

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(
          VanillaBpConfigurationBindingTest.ClasspathFactsConfiguration.class,
          VanillaBpConfigurationBindingTest.OverlayConfiguration.class)
      .withConfiguration(
          AutoConfigurations.of(SpringBootMigrationAdapterAutoConfiguration.class));

  /** Loads both documents of the YAML file and puts the command line above them. */
  private static void addSections(
      final ConfigurableEnvironment environment) {

    // a later document of a YAML file wins over an earlier one, as in Spring Boot
    for (final var document : yamlDocuments()) {
      environment
          .getPropertySources()
          .addFirst(document);
    }
    environment
        .getPropertySources()
        .addFirst(new SimpleCommandLinePropertySource(COMMAND_LINE));

  }

  private static List<EnumerablePropertySource<?>> yamlDocuments() {

    try {
      final var documents = new ArrayList<EnumerablePropertySource<?>>();
      new YamlPropertySourceLoader()
          .load(SECTIONS, new ClassPathResource(SECTIONS))
          .forEach(document -> documents.add((EnumerablePropertySource<?>) document));
      return documents;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

  }

  private static StandardEnvironment environmentWithSections() {

    final var environment = new StandardEnvironment();
    addSections(environment);
    return environment;

  }

  @Test
  @DisplayName("Spring Boot reads an empty YAML section as an empty value, and a plain binding loses the keys")
  public void anEmptySectionIsAnEmptyValue() {

    // the cause. If Spring Boot stops doing this, EmptySections is not needed any more
    final var firstDocument = yamlDocuments().getFirst();
    assertEquals("", String.valueOf(firstDocument.getProperty("vanillabp.adapters.saas")));
    assertEquals("", String.valueOf(firstDocument.getProperty("vanillabp.workflow-modules.test-module.adapters.saas")));

    // and the binding without the handler loses the keys of the second document and of the
    // command line
    final var plain = Binder
        .get(environmentWithSections())
        .bind(MigrationAdapterProperties.PREFIX, Bindable.of(VanillaBpConfigurationProperties.class))
        .orElseGet(VanillaBpConfigurationProperties::new);
    assertNull(plain
        .getAdapters()
        .get("saas")
        .getType());
    assertNull(plain
        .getAdapters()
        .get("saas")
        .getDeploymentFailure());

  }

  @Test
  @DisplayName("The binder of the adapters' bean registrars keeps the keys of an empty section")
  public void theRegistrarFindsTheAdapterIdByItsType() {

    final var adapterIds = new ArrayList<String>();
    AdapterBeanRegistrarSupport.forEachConfiguredAdapterId(environmentWithSections(), "dummy", adapterIds::add);

    // without the type of the second document, the id would count as the type 'saas'
    assertEquals(List.of("saas"), adapterIds);

  }

  @Test
  @DisplayName("The bind handler keeps the keys of an empty adapter section and of an empty module section")
  public void theBindHandlerKeepsTheKeys() {

    final var environment = environmentWithSections();
    // an id written as nothing but '{}', with no key anywhere below it
    environment
        .getPropertySources()
        .addLast(new MapPropertySource("bare id", Map.of("vanillabp.adapters.dummy", "")));

    final var properties = Binder
        .get(environment)
        .bind(
            MigrationAdapterProperties.PREFIX,
            Bindable.of(VanillaBpConfigurationProperties.class),
            EmptySections.bindHandler())
        .orElseGet(VanillaBpConfigurationProperties::new);

    final var saas = properties
        .getAdapters()
        .get("saas");
    assertEquals("dummy", saas.getType());
    assertEquals(DeploymentFailurePolicy.WARN, saas.getDeploymentFailure());
    assertEquals(
        "classpath:from-the-command-line",
        properties
            .getWorkflowModules()
            .get("test-module")
            .getAdapters()
            .get("saas")
            .getResourcesLocation());
    // the bare id is still configured. It has no keys, so it stays an empty section
    assertTrue(properties
        .getAdapters()
        .containsKey("dummy"));
    assertNull(properties
        .getAdapters()
        .get("dummy")
        .getType());

  }

  @Test
  @DisplayName("The bind handler keeps the keys when the whole tree is written as '{}'")
  public void theBindHandlerKeepsTheKeysBelowAnEmptyTree() {

    final var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("empty tree", Map.of(MigrationAdapterProperties.PREFIX, "")));
    environment
        .getPropertySources()
        .addFirst(new SimpleCommandLinePropertySource(
            "--vanillabp.adapters.saas.type=dummy", "--vanillabp.resources-location=classpath:from-the-command-line"));

    final var properties = Binder
        .get(environment)
        .bind(
            MigrationAdapterProperties.PREFIX,
            Bindable.of(VanillaBpConfigurationProperties.class),
            EmptySections.bindHandler())
        .orElseGet(VanillaBpConfigurationProperties::new);

    assertEquals("classpath:from-the-command-line", properties.getResourcesLocation());
    assertEquals(
        "dummy",
        properties
            .getAdapters()
            .get("saas")
            .getType());

  }

  @Test
  @DisplayName("A booted application keeps the keys of empty sections, in the core properties and in an overlay")
  public void aBootedApplicationKeepsTheKeys() {

    contextRunner
        .withInitializer(AnEmptySectionKeepsTheKeysTest::addSectionsToTheContext)
        .run(context -> {

          assertNull(context.getStartupFailure(), "context should start");

          final var properties = context.getBean(MigrationAdapterProperties.class);
          assertEquals(Map.of("saas", "dummy"), properties.adapterTypes());
          assertEquals(DeploymentFailurePolicy.WARN, properties.getDeploymentFailureFor("saas"));
          final var resourcesLocation = properties
              .getAdapterResourcesLocationsFor("test-module", "saas")
              .getFirst();
          assertEquals("classpath:from-the-command-line", resourcesLocation.location());
          assertFalse(resourcesLocation.vanillaBpBpmn());

          // the overlay class of an adapter is bound the same way
          final var overlay = context
              .getBean(VanillaBpConfigurationBindingTest.TestAdapterOverlayProperties.class)
              .getAdapters()
              .get("saas");
          assertNotNull(overlay);
          assertEquals("http://saas:26500", overlay.getRestAddress());

        });

  }

  private static void addSectionsToTheContext(
      final ConfigurableApplicationContext context) {

    addSections(context.getEnvironment());

  }

}
