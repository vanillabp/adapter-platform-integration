package io.vanillabp.integration.test.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.DeliveryProperties;
import io.vanillabp.integration.adapter.migration.config.ElectionProperties;
import io.vanillabp.integration.adapter.migration.config.MetricsProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.config.TransactionsProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterCacheProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Every key of a section of the core model is proposed and described.
 * <p>
 * The annotation processor writes what it compiles, and the sections below
 * <code>vanillabp</code> arrive as a dependency, so it sees the section and nothing inside
 * it. Whoever adds a key to one of those classes therefore has to write it into
 * <code>META-INF/additional-spring-configuration-metadata.json</code> by hand, and nothing
 * reminds them. This test is the reminder: it reads the fields of each section and looks
 * every one of them up.
 */
@ExtendWith(SuppressOutputExtension.class)
public class EveryKeyOfASectionIsDescribedTest {

  /**
   * Where the sections of the core model live. A field of that package is a section of its
   * own and is walked into, anything else is a key.
   */
  private static final String SECTIONS_PACKAGE = MigrationAdapterProperties.class.getPackageName();

  /**
   * Reads every section of the core model and looks each of its keys up in the metadata.
   *
   * @throws IOException If the classpath cannot be read
   */
  @Test
  @DisplayName("Every key of a section of the core model is described")
  public void everyKeyOfASectionIsDescribed() throws IOException {

    final var described = describedKeys();
    assertTrue(
        described.containsKey("vanillabp.outbox.retention"),
        """
            No configuration metadata of VanillaBP on the classpath! It is generated while the \
            main sources are compiled, so run this test through the build.""");

    final var missing = new TreeSet<String>();
    collectKeysMissingADescription(DeliveryProperties.class, "vanillabp.delivery", described, missing);
    collectKeysMissingADescription(ElectionProperties.class, "vanillabp.election", described, missing);
    collectKeysMissingADescription(MetricsProperties.class, "vanillabp.metrics", described, missing);
    collectKeysMissingADescription(TransactionsProperties.class, "vanillabp.transactions", described, missing);
    collectKeysMissingADescription(
        WorkflowAdapterCacheProperties.class,
        "vanillabp.workflow-adapter-cache",
        described,
        missing);
    collectKeysMissingADescription(PhaseTwoOutboxProperties.class, "vanillabp.outbox", described, missing);

    assertTrue(
        missing.isEmpty(),
        """
            These keys of the core model are missing from the configuration metadata, so a \
            development environment neither proposes nor explains them: %s
            The annotation processor does not descend into a type which arrives as a dependency, \
            so describe each of them in \
            'META-INF/additional-spring-configuration-metadata.json' of this module."""
            .formatted(missing));

  }

  /**
   * Walks one section, a key at a time, and collects what the metadata does not describe.
   *
   * @param section The class binding that section
   * @param prefix The key of that section, without a trailing dot
   * @param described What the metadata describes
   * @param missing Where the keys without a description are collected
   */
  private static void collectKeysMissingADescription(
      final Class<?> section,
      final String prefix,
      final Map<String, String> described,
      final TreeSet<String> missing) {

    for (final var field : section.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
        continue;
      }
      final var key = "%s.%s".formatted(prefix, relaxedName(field.getName()));
      final var type = field.getType();
      if (!type.isEnum() && type.getPackageName().equals(SECTIONS_PACKAGE)) {
        collectKeysMissingADescription(type, key, described, missing);
        continue;
      }
      final var description = described.get(key);
      if ((description == null) || description.isBlank()) {
        missing.add(key);
      }
    }

  }

  /**
   * @return What each key of our metadata says about itself, empty text where it says
   *         nothing
   * @throws IOException If the classpath cannot be read
   */
  private static Map<String, String> describedKeys() throws IOException {

    final var descriptions = new HashMap<String, String>();
    SpringConfigurationMetadata
        .propertiesOf(SpringConfigurationMetadata.GENERATED)
        .forEach(property -> descriptions.put(
            property.path("name").asText(),
            property.path("description").asText("")));
    return descriptions;

  }

  /**
   * @param fieldName The name the field carries
   * @return The name an application writes, dashes instead of capitals
   */
  private static String relaxedName(
      final String fieldName) {

    final var name = new StringBuilder();
    for (final var character : fieldName.toCharArray()) {
      if (Character.isUpperCase(character)) {
        name
            .append('-')
            .append(Character.toLowerCase(character));
      } else {
        name.append(character);
      }
    }
    return name.toString();

  }

}
