package io.vanillabp.integration.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Whether a development environment offers every property an application may write.
 * <p>
 * Spring Boot builds that list from
 * <code>META-INF/additional-spring-configuration-metadata.json</code>, and VanillaBP writes
 * the file by hand, because the properties are bound from a class of the platform-neutral
 * core which no Spring annotation processor ever sees. A file written by hand is a file
 * somebody forgets: a property added without its entry is offered by nobody, is marked as
 * unknown in the editor, and is found only by whoever reads the documentation.
 * <p>
 * So the two sides are compared here. The left side is the property classes, walked the way
 * the binder walks them; the right side is the file. A property on one side and not on the
 * other fails this test, which is the only way the next one does not slip through as well.
 * <p>
 * Quarkus needs none of this: it describes its properties from the config mapping itself.
 */
@ExtendWith(SuppressOutputExtension.class)
public class EveryPropertyIsOfferedByTheIdeTest {

  private static final String METADATA = "META-INF/additional-spring-configuration-metadata.json";

  /**
   * The types a binder writes a value into rather than descending through. A map of
   * strings onto a configuration class is one of them: what stands below it is an adapter
   * id or a workflow module id, so the file describes the map and stops there.
   */
  private static final Set<Class<?>> VALUE_TYPES = Set.of(
      String.class,
      Boolean.class,
      boolean.class,
      Integer.class,
      int.class,
      Long.class,
      long.class,
      Double.class,
      double.class,
      Duration.class,
      LocalTime.class);

  @Test
  @DisplayName("Every property of the configuration classes stands in the metadata file")
  public void everyPropertyIsDescribed() throws Exception {

    final var described = namesOfTheMetadataFile();
    final var bindable = new TreeSet<String>();
    collectInto(bindable, MigrationAdapterProperties.class, "vanillabp");

    final var missing = new TreeSet<>(bindable);
    missing.removeAll(described);

    assertEquals(
        Set.of(),
        missing,
        "a property nobody describes is a property no development environment offers - add it to "
            + METADATA);

  }

  @Test
  @DisplayName("The metadata file describes no property which does not exist")
  public void nothingIsDescribedWhichNobodyBinds() throws Exception {

    final var bindable = new TreeSet<String>();
    collectInto(bindable, MigrationAdapterProperties.class, "vanillabp");

    final var withoutAProperty = new TreeSet<>(namesOfTheMetadataFile());
    withoutAProperty.removeAll(bindable);

    assertEquals(
        Set.of(),
        withoutAProperty,
        "a key which was renamed or removed keeps being offered until its entry goes as well");

  }

  @Test
  @DisplayName("The walk really reaches the nested sections")
  public void theWalkReachesWhatItClaimsTo() {

    final var bindable = new TreeSet<String>();
    collectInto(bindable, MigrationAdapterProperties.class, "vanillabp");

    // one of each shape, so a walk which quietly stops early fails here rather than in a
    // comparison which then looks like a file somebody kept up to date
    assertTrue(bindable.contains("vanillabp.resources-location"), bindable.toString());
    assertTrue(bindable.contains("vanillabp.outbox.mongo.delivery-collection"), bindable.toString());
    assertTrue(bindable.contains("vanillabp.outbox.housekeeping.zone"), bindable.toString());
    assertTrue(bindable.contains("vanillabp.adapters"), bindable.toString());

  }

  private static Set<String> namesOfTheMetadataFile() throws Exception {

    try (var metadata = EveryPropertyIsOfferedByTheIdeTest.class
        .getClassLoader()
        .getResourceAsStream(METADATA)) {
      final var file = new ObjectMapper().readTree(metadata);
      final var names = new TreeSet<String>();
      file
          .get("properties")
          .forEach(property -> names.add(property.get("name").asText()));
      return names;
    }

  }

  /**
   * Walks one configuration class the way the binder walks it: a getter is a property, and
   * a getter whose type is a configuration class of VanillaBP is a section to descend
   * into.
   */
  private static void collectInto(
      final Set<String> keys,
      final Class<?> type,
      final String prefix) {

    for (final var getter : gettersOf(type)) {
      final var key = prefix
          + "."
          + kebabCase(nameOf(getter));
      if (isASection(getter.getReturnType())) {
        collectInto(keys, getter.getReturnType(), key);
        continue;
      }
      keys.add(key);
    }

  }

  /**
   * Whether a type is a section of the configuration rather than a value written into
   * one.
   */
  private static boolean isASection(
      final Class<?> type) {

    return type
        .getName()
        .startsWith("io.vanillabp.") && !type.isEnum() && !VALUE_TYPES.contains(type);

  }

  private static List<Method> gettersOf(
      final Class<?> type) {

    final var getters = new ArrayList<Method>();
    for (final var method : type.getMethods()) {
      if (Modifier.isStatic(method.getModifiers()) || (method.getParameterCount() != 0) || !method
          .getDeclaringClass()
          .getName()
          .startsWith("io.vanillabp.")) {
        continue;
      }
      final var name = method.getName();
      if (!name.startsWith("get") && !name.startsWith("is")) {
        continue;
      }
      if (isDerived(method)) {
        continue;
      }
      getters.add(method);
    }
    return getters;

  }

  /**
   * Whether a getter answers something derived rather than something bound. The binder
   * writes through a SETTER, so a read-only getter is an answer of the configuration and
   * not a key of it.
   */
  private static boolean isDerived(
      final Method getter) {

    final var setter = "set"
        + nameOf(getter);
    for (final var method : getter.getDeclaringClass().getMethods()) {
      if (method.getName().equals(setter) && (method.getParameterCount() == 1)) {
        return false;
      }
    }
    return true;

  }

  private static String nameOf(
      final Method getter) {

    return getter.getName().startsWith("is")
        ? getter.getName().substring(2)
        : getter.getName().substring(3);

  }

  private static String kebabCase(
      final String name) {

    final var key = new StringBuilder();
    for (var index = 0; index < name.length(); index++) {
      final var character = name.charAt(index);
      if (Character.isUpperCase(character) && (index > 0)) {
        key.append('-');
      }
      key.append(Character.toLowerCase(character));
    }
    return key
        .toString()
        .toLowerCase(Locale.ROOT);

  }

}
