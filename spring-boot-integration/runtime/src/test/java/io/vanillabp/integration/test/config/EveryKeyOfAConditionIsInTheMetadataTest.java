package io.vanillabp.integration.test.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A VanillaBP auto-configuration decides by some keys. An application is meant to write each
 * of them, so a development environment has to propose them.
 * <p>
 * A condition names its key as a string and needs nothing else. That is how
 * <code>vanillabp.outbox.gruelbox.enabled</code> lived for a while. Applications wrote it, but
 * it was not in the properties model. So it was missing from the configuration metadata an
 * IDE reads. This test asks the conditions which keys they read. It looks up each key in that
 * metadata. So the next key which only a condition knows fails here, not in somebody's editor.
 * <p>
 * The metadata is what the build produces. The annotation processor writes
 * <code>META-INF/spring-configuration-metadata.json</code> next to the classes. The keys below
 * the sections of the core model come from
 * <code>META-INF/additional-spring-configuration-metadata.json</code>. The processor does not
 * go down into types which arrive as a dependency.
 * <p>
 * Only the metadata of VanillaBP's own artifacts counts here. Every framework jar ships a file
 * of the same name. If one of them described a <code>vanillabp</code> key, this test would
 * pass while our own metadata says nothing. {@link #theMetadataReadIsOursAlone()} rules that
 * out.
 */
@ExtendWith(SuppressOutputExtension.class)
public class EveryKeyOfAConditionIsInTheMetadataTest {

  /**
   * Where Spring Boot lists the auto-configurations of a module. Every jar with
   * auto-configurations brings this file. So the code below filters the lines by package.
   */
  private static final String AUTO_CONFIGURATIONS = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

  /**
   * A key which this module's own metadata describes. {@link #theMetadataReadIsOursAlone()}
   * uses it to prove that the test read our file and not one of the framework.
   */
  private static final String A_KEY_OF_OUR_OWN = "vanillabp.outbox.jdbc.enabled";

  /**
   * The section every key of VanillaBP starts with. What a condition names outside it
   * belongs to somebody else.
   */
  private static final String OUR_SECTION = MigrationAdapterProperties.PREFIX
      + ".";

  /**
   * The package of the auto-configurations this test is about. The classpath lists the
   * auto-configurations of the framework in the same file.
   */
  private static final String OUR_PACKAGE = "io.vanillabp.";

  /**
   * Asks every condition of every VanillaBP auto-configuration which keys it reads. Then it
   * looks up each key in the configuration metadata of the classpath.
   *
   * @throws IOException If the classpath cannot be read
   */
  @Test
  @DisplayName("Every vanillabp key a condition reads is in the configuration metadata")
  public void everyKeyOfAConditionIsInTheMetadata() throws IOException {

    final var metadata = keysOfOurMetadata();
    assertFalse(
        metadata.isEmpty(),
        """
            No configuration metadata of VanillaBP on the classpath! It is generated while the \
            main sources are compiled, so run this test through the build.""");

    final var missing = new TreeSet<String>();
    for (final var autoConfiguration : autoConfigurations()) {
      for (final var key : keysReadByConditionsOf(autoConfiguration)) {
        if (!metadata.contains(key)) {
          missing.add("%s (read by %s)".formatted(key, autoConfiguration.getSimpleName()));
        }
      }
    }

    assertTrue(
        missing.isEmpty(),
        """
            These keys are read by a condition but unknown to the configuration metadata, so no \
            development environment proposes them: %s
            Add each of them to a properties class - the core model where every platform has the \
            key, this module where Spring Boot has it alone. A key this module compiles is \
            described by the javadoc of its field, a key whose type arrives as a dependency in \
            'META-INF/additional-spring-configuration-metadata.json'."""
            .formatted(missing));

  }

  /**
   * The counter-check of the test above. It answers from OUR metadata, not from whatever the
   * classpath holds. Every framework jar ships a file of the same name. A foreign file which
   * describes a <code>vanillabp</code> key would make the guard pass while our own metadata
   * says nothing.
   *
   * @throws IOException If the classpath cannot be read
   */
  @Test
  @DisplayName("The metadata this test reads is ours alone")
  public void theMetadataReadIsOursAlone() throws IOException {

    final var keys = keysOfOurMetadata();

    assertTrue(
        keys.contains(A_KEY_OF_OUR_OWN),
        "'%s' is described by this module, so our own metadata has to know it"
            .formatted(A_KEY_OF_OUR_OWN));
    assertTrue(
        keys.stream().allMatch(key -> key.startsWith(OUR_SECTION)),
        """
            Our metadata describes keys below '%s' and nothing else, so a key from another \
            section means a foreign file was read: %s"""
            .formatted(
                OUR_SECTION,
                keys.stream().filter(key -> !key.startsWith(OUR_SECTION)).toList()));

  }

  /**
   * @return The auto-configuration classes of VanillaBP, read from the file Spring Boot
   *         itself reads
   * @throws IOException If the classpath cannot be read
   */
  private static Collection<Class<?>> autoConfigurations() throws IOException {

    final var classes = new ArrayList<Class<?>>();
    for (final var resource : resources(AUTO_CONFIGURATIONS)) {
      new String(resource.openStream().readAllBytes())
          .lines()
          .map(String::trim)
          .filter(line -> line.startsWith(OUR_PACKAGE))
          .forEach(className -> classes.add(loadClass(className)));
    }
    return classes;

  }

  /**
   * Loads a class without initializing it. An auto-configuration reports a misconfiguration
   * while it is built, and nothing here builds one.
   *
   * @param className The name read from the list of auto-configurations
   * @return The class
   */
  private static Class<?> loadClass(
      final String className) {

    try {
      return Class.forName(className, false, classLoader());
    } catch (final ClassNotFoundException e) {
      throw new IllegalStateException(
          "The auto-configuration '%s' is listed but not on the classpath!".formatted(className), e);
    }

  }

  /**
   * The VanillaBP keys which the conditions of one auto-configuration read. These are the
   * conditions of the class itself and of its bean methods.
   *
   * @param autoConfiguration The auto-configuration to read
   * @return The keys, each written the way an application writes it
   */
  private static Set<String> keysReadByConditionsOf(
      final Class<?> autoConfiguration) {

    final var keys = new TreeSet<String>();
    collectKeysOfConditions(autoConfiguration.getAnnotations(), keys);
    for (final var method : autoConfiguration.getDeclaredMethods()) {
      collectKeysOfConditions(method.getAnnotations(), keys);
    }
    return keys;

  }

  /**
   * Collects the keys of the two property conditions which Spring Boot offers. Both have the
   * same attributes, so the code reads both the same way.
   *
   * @param annotations What sits on a class or on one of its methods
   * @param keys Where the keys found are collected
   */
  private static void collectKeysOfConditions(
      final Annotation[] annotations,
      final Set<String> keys) {

    for (final var annotation : annotations) {
      if (annotation instanceof final ConditionalOnProperty condition) {
        collectKeysNamedBy(condition.prefix(), condition.name(), keys);
        collectKeysNamedBy(condition.prefix(), condition.value(), keys);
      } else if (annotation instanceof final ConditionalOnBooleanProperty condition) {
        collectKeysNamedBy(condition.prefix(), condition.name(), keys);
        collectKeysNamedBy(condition.prefix(), condition.value(), keys);
      }
    }

  }

  /**
   * Writes the keys of one condition the way an application writes them. A prefix goes in
   * front of every name. A complete name comes without a prefix.

   *
   * @param prefix The section of the condition, empty where the names are complete
   * @param names The names the condition was given, as 'name' or as 'value'
   * @param keys Where the keys are collected
   */
  private static void collectKeysNamedBy(
      final String prefix,
      final String[] names,
      final Set<String> keys) {

    final var section = prefix.isEmpty() || prefix.endsWith(".")
        ? prefix
        : prefix
            + ".";
    for (final var name : names) {
      final var key = section + name;
      if (key.startsWith(OUR_SECTION)) {
        keys.add(key);
      }
    }

  }

  /**
   * @return Every key the configuration metadata of VanillaBP's own artifacts knows
   * @throws IOException If the classpath cannot be read
   */
  private static Set<String> keysOfOurMetadata() throws IOException {

    final var keys = new TreeSet<String>();
    SpringConfigurationMetadata
        .propertiesOf(SpringConfigurationMetadata.GENERATED)
        .forEach(property -> keys.add(property.path("name").asText()));
    return keys;

  }

  /**
   * @param name The resource every module may bring
   * @return What the classpath has under that name
   * @throws IOException If the classpath cannot be read
   */
  private static Collection<URL> resources(
      final String name) throws IOException {

    return Collections.list(classLoader().getResources(name));

  }

  /**
   * @return The class loader the test runs in
   */
  private static ClassLoader classLoader() {

    return EveryKeyOfAConditionIsInTheMetadataTest.class.getClassLoader();

  }

}
