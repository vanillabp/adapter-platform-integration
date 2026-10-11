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
 * <code>META-INF/additional-spring-configuration-metadata.json</code>. VanillaBP writes the
 * file by hand. The properties are bound from a class of the platform-neutral core, and no
 * Spring annotation processor ever sees that class. But people forget to update a file
 * written by hand. A property added without its entry is offered by nobody. The editor marks
 * it as unknown, and only people who read the documentation find it.
 * <p>
 * So this test compares the two sides. The left side is the property classes, walked the way
 * the binder walks them. The right side is the file. A property on one side and not on the
 * other fails this test. Only that stops the next missing entry from slipping through.
 * <p>
 * Quarkus needs none of this. It describes its properties from the config mapping itself.
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

    // one key of each shape. A walk which quietly stops early fails here. Otherwise the
    // comparison would pass, and the file would look as if somebody kept it up to date
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
   * Walks one configuration class the way the binder walks it. A getter is a property. A
   * getter whose type is a configuration class of VanillaBP is a section, and the walk goes
   * down into it.
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
   * Whether a getter returns something derived, and not something bound. The binder writes
   * through a SETTER. So a getter without a setter is something the configuration computes,
   * not one of its keys.
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
