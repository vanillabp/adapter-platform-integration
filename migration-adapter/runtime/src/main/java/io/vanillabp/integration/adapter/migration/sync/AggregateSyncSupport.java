package io.vanillabp.integration.adapter.migration.sync;

import java.beans.Introspector;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.adapter.spi.WorkflowAggregateSync;
import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.SyncWithBPMS;
import lombok.extern.slf4j.Slf4j;

/**
 * THE sync model (story 28): which attributes of a workflow aggregate are shared
 * with the BPMS, and what their values look like. BPMS-neutral by design - the
 * adapters only decide their {@link AggregateSyncMode} default and what to do with
 * the result.
 *
 * <h2>Inheritance - the principle of least astonishment</h2>
 *
 * Every attribute <b>inherits the behavior of its owner</b> until the application
 * says otherwise:
 *
 * <ol>
 * <li>the outermost default is the ADAPTER's ({@link AggregateSyncMode}),</li>
 * <li>an annotation on the aggregate CLASS ({@code @SyncWithBPMS} /
 * {@code @NoSyncWithBPMS}) overrides it for all of its attributes,</li>
 * <li>an annotation on an attribute (field or getter) overrides it for that
 * attribute AND everything below it - a nested object's attributes and a
 * collection's elements inherit from the attribute they belong to,</li>
 * <li>an annotation on a nested TYPE overrides what that type's members inherited
 * through the attribute (so a DTO may narrow what it exposes wherever it is
 * used).</li>
 * </ol>
 *
 * A DTO carrying no annotation therefore behaves exactly like the attribute
 * holding it - it is never "fully shared" on its own account.
 *
 * <h2>Which attributes exist</h2>
 *
 * Readable JavaBean properties (public getters incl. {@code isX()}) - the
 * intention-revealing getters the wiki recommends are attributes like any other.
 * A getter's annotation wins over the annotation of a field of the same name.
 * {@code getClass} and getters taking arguments are ignored, as are static and
 * synthetic members.
 *
 * <h2>Which values are produced</h2>
 *
 * <ul>
 * <li>{@code null}, primitives/wrappers, {@link String}, {@link Number},
 * {@link Boolean} and {@link Character} are taken as they are,</li>
 * <li>enums become their {@link Enum#name()}, temporal values and everything else
 * without accessible properties become their {@code toString()},</li>
 * <li>collections and arrays become {@link List}s of converted elements, maps
 * become maps with their keys converted to strings,</li>
 * <li>any other object becomes a {@link Map} of its shared attributes.</li>
 * </ul>
 *
 * The result is deliberately made of plain JDK types: every BPMS' variable
 * serialization (and Camunda 8's FEEL) copes with them.
 */
@Slf4j
public class AggregateSyncSupport implements WorkflowAggregateSync {

  /**
   * How deep nested objects are followed - a guard against cyclic object graphs
   * (a workflow aggregate is not a graph database).
   */
  public static final int MAX_DEPTH = 10;

  /**
   * The readable properties per class, resolved once (reflection is not free and
   * sync points are hot paths).
   */
  private final Map<Class<?>, List<Property>> propertiesByClass = new ConcurrentHashMap<>();

  /**
   * One readable attribute of a class: its name, how to read it and whether the
   * application annotated it.
   */
  private record Property(
                          String name,
                          Method getter,
                          Boolean synced) {

    Object read(
        final Object owner) {

      try {
        return getter.invoke(owner);
      } catch (final Exception e) {
        throw new IllegalStateException(
            "Could not read the attribute '%s' of '%s' while collecting the values shared with the BPMS!"
                .formatted(name, owner
                    .getClass()
                    .getName()), e);
      }

    }

  }

  @Override
  public Map<String, Object> syncedValues(
      final Object workflowAggregate,
      final AggregateSyncMode adapterDefault) {

    if (workflowAggregate == null) {
      return Map.of();
    }
    final var inherited = annotationOf(workflowAggregate.getClass());
    final var effective = inherited != null
        ? inherited
        : adapterDefault == AggregateSyncMode.FULL;
    return valuesOf(workflowAggregate, effective, 0);

  }

  /**
   * The shared attributes of one object.
   *
   * @param owner The object
   * @param inherited Whether its attributes are shared unless annotated otherwise
   * @param depth The current nesting depth
   */
  private Map<String, Object> valuesOf(
      final Object owner,
      final boolean inherited,
      final int depth) {

    final var values = new LinkedHashMap<String, Object>();
    for (final var property : propertiesOf(owner.getClass())) {
      final var synced = property.synced() != null
          ? property.synced()
          : inherited;
      if (!synced) {
        continue;
      }
      values.put(property.name(), convert(property.read(owner), synced, depth + 1));
    }
    return values;

  }

  /**
   * Converts one value into a BPMS-compatible representation (see the class
   * comment).
   *
   * @param value The value
   * @param inherited What nested attributes inherit
   * @param depth The current nesting depth
   */
  private Object convert(
      final Object value,
      final boolean inherited,
      final int depth) {

    if ((value == null) || (value instanceof String) || (value instanceof Number) || (value instanceof Boolean) || (value instanceof Character)) {
      return value;
    }
    if (value instanceof Enum<?> enumValue) {
      return enumValue.name();
    }
    if (value instanceof Collection<?> collection) {
      return collection
          .stream()
          .map(element -> convert(element, inherited, depth))
          .toList();
    }
    if (value.getClass().isArray()) {
      final var elements = new LinkedList<Object>();
      final var length = java.lang.reflect.Array.getLength(value);
      for (var index = 0; index < length; ++index) {
        elements.add(convert(java.lang.reflect.Array.get(value, index), inherited, depth));
      }
      return List.copyOf(elements);
    }
    if (value instanceof Map<?, ?> map) {
      final var converted = new LinkedHashMap<String, Object>();
      map.forEach((
          key,
          mapValue) -> converted.put(String.valueOf(key), convert(mapValue, inherited, depth)));
      return converted;
    }

    if (isJdkValueType(value.getClass())) {
      // JDK value types (java.time.*, UUID, Date, Duration, Locale, ...) do have
      // readable getters, but their string form is what a BPMS - and a BPMN
      // expression - can work with
      return String.valueOf(value);
    }

    if (depth >= MAX_DEPTH) {
      // cyclic or absurdly deep object graph: stop following and share a
      // representation instead of looping forever
      log.debug(
          "Stopped collecting values shared with the BPMS at depth {} (class '{}') - "
              + "nested objects are followed at most {} levels deep",
          depth,
          value
              .getClass()
              .getName(),
          MAX_DEPTH);
      return String.valueOf(value);
    }

    final var properties = propertiesOf(value.getClass());
    if (properties.isEmpty()) {
      // no readable attributes (e.g. java.time types, UUID, BigDecimal-likes):
      // every BPMS understands the string form
      return String.valueOf(value);
    }
    // a nested TYPE's own annotation overrides what its members inherited through
    // the attribute holding it
    final var ofType = annotationOf(value.getClass());
    return valuesOf(value, ofType != null
        ? ofType
        : inherited, depth);

  }

  /**
   * Whether the given type is a JDK value type shared in its string form (see
   * {@link #convert}). Collections, maps and arrays are handled before this check.
   */
  private static boolean isJdkValueType(
      final Class<?> clazz) {

    final var packageName = clazz.getPackageName();
    return packageName.startsWith("java.") || packageName.startsWith("javax.");

  }

  /**
   * @param annotated A class, field or getter
   * @return {@code true}/{@code false} if the application annotated it,
   *         <code>null</code> if it inherits
   */
  private static Boolean annotationOf(
      final java.lang.reflect.AnnotatedElement annotated) {

    final var synced = annotated.isAnnotationPresent(SyncWithBPMS.class);
    final var notSynced = annotated.isAnnotationPresent(NoSyncWithBPMS.class);
    if (synced && notSynced) {
      throw new IllegalStateException(
          ("'%s' is annotated with both @SyncWithBPMS and @NoSyncWithBPMS! Decide whether its value "
              + "is shared with the BPMS - if it is meant to be shared only sometimes, model that as "
              + "an own (intention-revealing) getter.")
              .formatted(annotated));
    }
    if (synced) {
      return Boolean.TRUE;
    }
    if (notSynced) {
      return Boolean.FALSE;
    }
    return null;

  }

  /**
   * The readable properties of a class incl. their annotations, cached.
   */
  private List<Property> propertiesOf(
      final Class<?> clazz) {

    return propertiesByClass.computeIfAbsent(clazz, AggregateSyncSupport::determineProperties);

  }

  private static List<Property> determineProperties(
      final Class<?> clazz) {

    final var properties = new LinkedList<Property>();
    for (final var method : clazz.getMethods()) {
      final var name = propertyNameOf(method);
      if (name == null) {
        continue;
      }
      method.setAccessible(true);
      properties.add(new Property(name, method, annotationOf(fieldOrGetter(clazz, name, method))));
    }
    properties.sort(java.util.Comparator.comparing(Property::name));
    return List.copyOf(properties);

  }

  /**
   * The element carrying the application's annotation: the GETTER wins (an
   * intention-revealing getter is annotated there), otherwise the field of the
   * same name if there is one.
   */
  private static java.lang.reflect.AnnotatedElement fieldOrGetter(
      final Class<?> clazz,
      final String propertyName,
      final Method getter) {

    if (getter.isAnnotationPresent(SyncWithBPMS.class) || getter.isAnnotationPresent(NoSyncWithBPMS.class)) {
      return getter;
    }
    final var field = findField(clazz, propertyName);
    return field != null
        ? field
        : getter;

  }

  private static Field findField(
      final Class<?> clazz,
      final String propertyName) {

    var current = clazz;
    while ((current != null) && (current != Object.class)) {
      try {
        return current.getDeclaredField(propertyName);
      } catch (final NoSuchFieldException e) {
        current = current.getSuperclass();
      }
    }
    return null;

  }

  /**
   * @return The JavaBean property name of a readable getter or <code>null</code>
   */
  private static String propertyNameOf(
      final Method method) {

    if (method.getParameterCount() > 0) {
      return null;
    }
    if (Modifier.isStatic(method.getModifiers()) || method.isSynthetic()) {
      return null;
    }
    if (method.getDeclaringClass() == Object.class) {
      return null;
    }
    final var name = method.getName();
    if (name.equals("getClass")) {
      return null;
    }
    if (name.startsWith("get") && (name.length() > 3)) {
      return Introspector.decapitalize(name.substring(3));
    }
    if (name.startsWith("is") && (name
        .length() > 2) && ((method.getReturnType() == boolean.class) || (method.getReturnType() == Boolean.class))) {
      return Introspector.decapitalize(name.substring(2));
    }
    return null;

  }

}
