package io.vanillabp.integration.runtime.test.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The guard which used to be the compiler. MapStruct generated
 * {@link QuarkusMigrationAdapterPropertiesMapper} with {@code unmappedSourcePolicy} and
 * {@code unmappedTargetPolicy} on ERROR, so a property added to only one of the two sides
 * broke the build on the spot. The mapper is written by hand since story 639 (see
 * decision 81 in the repository's DECISIONS.md), and hand-written code says nothing at
 * all when a property is forgotten.
 * <p>
 * So this test says it instead. It builds a stand-in for the SmallRye
 * {@code @ConfigMapping} interface which answers every accessor with a made-up value and
 * writes down that it was asked, maps it twice with two sets of values which differ in
 * every single leaf, and then demands two things. Every accessor of the interface was
 * read at least once, so a new key cannot be bound and dropped. And every field of the
 * core model came out different in the two runs, so a field nobody writes, or a field
 * written from a constant instead of from the configuration, is reported by name.
 * <p>
 * {@link QuarkusMigrationAdapterPropertiesMapperTest} stands next to it and pins what the
 * values mean: the unwrapping of an {@code Optional}, the empty-list default, the null
 * which lets the core walk one level further.
 */
@ExtendWith(SuppressOutputExtension.class)
public class QuarkusMigrationAdapterPropertiesMapperGuardTest {

  /**
   * The fields of the core model which the mapper deliberately leaves alone, named
   * {@code SimpleClassName.fieldName}. Two of them are filled while the application
   * starts, the other three are back-references the core links once the whole tree
   * stands. Anything else missing from the mapping is a mistake, which is the point of
   * this test.
   */
  private static final Set<String> FIELDS_THE_MAPPER_LEAVES_TO_THE_CORE = Set
      .of(
          "MigrationAdapterProperties.conventionalResourcesLocations",
          "MigrationAdapterProperties.startupFindings",
          "WorkflowModuleAdapterProperties.workflowModuleId",
          "WorkflowAdapterProperties.bpmnProcessId",
          "WorkflowAdapterProperties.workflowModule");

  /**
   * Where the core model lives. A field whose type sits in this package and is no enum is
   * a section of its own, so the check walks into it instead of comparing it.
   */
  private static final String CORE_MODEL_PACKAGE = "io.vanillabp.integration.adapter.migration.config.";

  /**
   * The single key every map of sections is filled with. Both runs use it, so the two
   * results can be compared section by section.
   */
  private static final String THE_ONE_KEY = "one";

  @Test
  @DisplayName("every property of the SmallRye interface is read by the mapper")
  public void everyPropertyOfTheSmallRyeInterfaceIsRead() {

    final var read = new LinkedHashSet<String>();

    QuarkusMigrationAdapterPropertiesMapper.INSTANCE
        .toCore(aConfigurationRecordingWhatIsRead(QuarkusMigrationAdapterProperties.class, 0, read));

    final var unread = new TreeSet<String>();
    for (final var section : theSectionsOfTheSmallRyeInterface()) {
      for (final var accessor : theAccessorsOf(section)) {
        final var name = section.getSimpleName()
            + "."
            + accessor.getName();
        if (!read.contains(name)) {
          unread.add(name);
        }
      }
    }

    if (!unread.isEmpty()) {
      fail("the mapper never reads "
          + String.join(", ", unread)
          + ". Every key an application may write has to arrive in the core model,"
          + " so either copy it there or say in the mapper why it stays behind.");
    }

  }

  @Test
  @DisplayName("every field of the core model is written by the mapper")
  public void everyFieldOfTheCoreModelIsWritten() {

    final var read = new LinkedHashSet<String>();

    final var first = QuarkusMigrationAdapterPropertiesMapper.INSTANCE
        .toCore(aConfigurationRecordingWhatIsRead(QuarkusMigrationAdapterProperties.class, 0, read));
    final var second = QuarkusMigrationAdapterPropertiesMapper.INSTANCE
        .toCore(aConfigurationRecordingWhatIsRead(QuarkusMigrationAdapterProperties.class, 1, read));

    demandEveryFieldDiffers(first, second, "vanillabp");

  }

  /**
   * Walks two mapped sections side by side and demands that each of their fields came out
   * different. The two runs were fed values which differ everywhere, so a field which is
   * equal in both is a field the mapper never touched.
   *
   * @param first The section mapped from the first set of values
   * @param second The section mapped from the second set of values
   * @param path Where in the tree this section sits, used in the failure message
   */
  private static void demandEveryFieldDiffers(
      final Object first,
      final Object second,
      final String path) {

    for (final var field : theFieldsOf(first.getClass())) {
      final var name = field.getDeclaringClass().getSimpleName()
          + "."
          + field.getName();
      if (FIELDS_THE_MAPPER_LEAVES_TO_THE_CORE.contains(name)) {
        continue;
      }
      field.setAccessible(true);
      try {
        demandTheValueDiffers(field.get(first), field.get(second), path
            + "."
            + field.getName());
      } catch (IllegalAccessException e) {
        throw new RuntimeException("cannot read "
            + name
            + " of the core model", e);
      }
    }

  }

  /**
   * Compares one value of the first run against the same value of the second run. A
   * section is walked into, a map of sections entry by entry, and everything else has to
   * differ.
   *
   * @param first The value of the first run
   * @param second The value of the second run
   * @param path Where in the tree this value sits, used in the failure message
   */
  private static void demandTheValueDiffers(
      final Object first,
      final Object second,
      final String path) {

    if ((first == null) && (second == null)) {
      fail(path
          + " stays empty although the configuration sets it."
          + " The mapper has to copy it onto the core model.");
    }
    if ((first == null) || (second == null)) {
      fail(path
          + " is copied in one run and not in the other, which no mapping should do.");
    }
    if (isASectionOfTheCoreModel(first.getClass())) {
      demandEveryFieldDiffers(first, second, path);
      return;
    }
    if ((first instanceof Map<?, ?> firstMap) && (second instanceof Map<?, ?> secondMap) && holdsSectionsOfTheCoreModel(
        firstMap)) {
      assertEquals(firstMap.keySet(), secondMap.keySet(), path
          + " maps other keys now");
      firstMap
          .forEach((
              key,
              section) -> demandTheValueDiffers(
                  section, secondMap.get(key), path
                      + "["
                      + key
                      + "]"));
      return;
    }
    assertNotEquals(first, second,
        path
            + " is the same although the two runs configured it differently,"
            + " so the mapper does not copy it from the configuration.");

  }

  /**
   * The fields one section of the core model carries, its inherited ones included. What
   * the compiler or a tool added is left out: only a field somebody declared is a field
   * somebody can forget to map.
   *
   * @param section The class of the mapped section
   * @return Its fields, from the class itself upwards
   */
  private static List<Field> theFieldsOf(
      final Class<?> section) {

    final var fields = new ArrayList<Field>();
    for (var current = section; current != Object.class; current = current.getSuperclass()) {
      for (final var field : current.getDeclaredFields()) {
        if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
          continue;
        }
        fields.add(field);
      }
    }
    return fields;

  }

  /**
   * Whether a value is a section of the core model, which is walked into rather than
   * compared. An enum of that package is a value like any other.
   *
   * @param type The runtime type of the value
   * @return Whether the check has to walk into it
   */
  private static boolean isASectionOfTheCoreModel(
      final Class<?> type) {

    return !type.isEnum() && type.getName().startsWith(CORE_MODEL_PACKAGE);

  }

  /**
   * Whether a map holds sections of the core model. The maps of adapters, workflow
   * modules, workflows and tasks do; the extension settings hold plain strings and are
   * compared as they are.
   *
   * @param map The mapped map
   * @return Whether its entries are sections
   */
  private static boolean holdsSectionsOfTheCoreModel(
      final Map<?, ?> map) {

    return map
        .values()
        .stream()
        .anyMatch(value -> (value != null) && isASectionOfTheCoreModel(value.getClass()));

  }

  /**
   * Every section of the SmallRye interface, found by following the return types from the
   * root. Nothing lists them, so nothing can forget to list a new one.
   *
   * @return The root interface and every section below it
   */
  private static Set<Class<?>> theSectionsOfTheSmallRyeInterface() {

    final var found = new LinkedHashSet<Class<?>>();
    collectSectionsOf(QuarkusMigrationAdapterProperties.class, found);
    return found;

  }

  /**
   * Adds every section this type leads to, itself included, and follows the return types
   * of its accessors. A type argument counts as well, which is how the sections behind a
   * map are found.
   *
   * @param type The type to look at
   * @param found Where the sections are collected
   */
  private static void collectSectionsOf(
      final Type type,
      final Set<Class<?>> found) {

    if (type instanceof ParameterizedType parameterized) {
      collectSectionsOf(parameterized.getRawType(), found);
      for (final var argument : parameterized.getActualTypeArguments()) {
        collectSectionsOf(argument, found);
      }
      return;
    }
    if (!(type instanceof Class<?> candidate)) {
      return;
    }
    if (!isASectionOfTheSmallRyeInterface(candidate) || !found.add(candidate)) {
      return;
    }
    for (final var accessor : theAccessorsOf(candidate)) {
      collectSectionsOf(accessor.getGenericReturnType(), found);
    }

  }

  /**
   * Whether a type is the SmallRye interface itself or one of the sections nested in it.
   *
   * @param type The type to judge
   * @return Whether the check covers it
   */
  private static boolean isASectionOfTheSmallRyeInterface(
      final Class<?> type) {

    return type.isInterface() && ((type == QuarkusMigrationAdapterProperties.class) || (type
        .getEnclosingClass() == QuarkusMigrationAdapterProperties.class));

  }

  /**
   * What SmallRye binds a key to: a method without parameters and without a body.
   *
   * @param section The interface to look at
   * @return Its accessors, in the order the source declares them
   */
  private static List<Method> theAccessorsOf(
      final Class<?> section) {

    final var accessors = new ArrayList<Method>();
    for (final var method : section.getDeclaredMethods()) {
      if (Modifier.isStatic(method.getModifiers()) || method.isDefault() || method
          .isSynthetic() || (method.getParameterCount() > 0)) {
        continue;
      }
      accessors.add(method);
    }
    return accessors;

  }

  /**
   * A stand-in for a section of the SmallRye interface. It answers every accessor with a
   * value made from the seed, so two seeds give two configurations which differ in every
   * leaf, and it writes down every accessor it was asked for.
   *
   * @param <T> The section type
   * @param section The interface to stand in for
   * @param seed Which of the two configurations this is
   * @param read Where the names of the accessors read are collected
   * @return The stand-in
   */
  @SuppressWarnings("unchecked")
  private static <T> T aConfigurationRecordingWhatIsRead(
      final Class<T> section,
      final int seed,
      final Set<String> read) {

    final InvocationHandler answerAndRecord = (
        proxy,
        method,
        arguments) -> {
      if (method.getDeclaringClass() == Object.class) {
        return switch (method.getName()) {
          case "toString" -> section.getSimpleName()
              + " of run "
              + seed;
          case "hashCode" -> System.identityHashCode(proxy);
          case "equals" -> proxy == arguments[0];
          default -> throw new UnsupportedOperationException(method.getName());
        };
      }
      read.add(method.getDeclaringClass().getSimpleName()
          + "."
          + method.getName());
      return aValueOf(method.getGenericReturnType(), seed, read);
    };

    return (T) Proxy
        .newProxyInstance(section.getClassLoader(), new Class<?>[]{
            section
        }, answerAndRecord);

  }

  /**
   * One made-up value of the given type, different for every seed. A type this method
   * does not know fails the test on purpose: a new kind of property has to be taught here
   * before the check can claim to cover it.
   *
   * @param type The type the accessor returns
   * @param seed Which of the two configurations this is
   * @param read Where the names of the accessors read are collected
   * @return The value
   */
  private static Object aValueOf(
      final Type type,
      final int seed,
      final Set<String> read) {

    if (type instanceof ParameterizedType parameterized) {
      final var raw = (Class<?>) parameterized.getRawType();
      final var arguments = parameterized.getActualTypeArguments();
      if (raw == Optional.class) {
        return Optional.of(aValueOf(arguments[0], seed, read));
      }
      if (raw == List.class) {
        return List.of(aValueOf(arguments[0], seed, read));
      }
      if (raw == Map.class) {
        final var map = new LinkedHashMap<String, Object>();
        map.put(THE_ONE_KEY, aValueOf(arguments[1], seed, read));
        return map;
      }
      return fail("the guard does not know how to fill a "
          + type
          + ". Teach it before adding a property of that type.");
    }
    if (!(type instanceof Class<?> raw)) {
      return fail("the guard does not know how to fill a "
          + type
          + ".");
    }
    if ((raw == String.class)) {
      return "value-"
          + seed;
    }
    if ((raw == boolean.class) || (raw == Boolean.class)) {
      return seed != 0;
    }
    if ((raw == int.class) || (raw == Integer.class)) {
      return 11 + seed;
    }
    if ((raw == long.class) || (raw == Long.class)) {
      return 11L + seed;
    }
    if (raw == Duration.class) {
      return Duration.ofSeconds(11 + seed);
    }
    if (raw == LocalTime.class) {
      return LocalTime.of(1 + seed, 0);
    }
    if (raw.isEnum()) {
      final var constants = raw.getEnumConstants();
      if (constants.length < 2) {
        return fail(raw.getSimpleName()
            + " has one constant only, so the guard cannot tell a copied value from a default.");
      }
      return constants[seed % constants.length];
    }
    if (raw.isInterface()) {
      return aConfigurationRecordingWhatIsRead(raw, seed, read);
    }
    return fail("the guard does not know how to fill a "
        + raw.getName()
        + ". Teach it before adding a property of that type.");

  }

}
