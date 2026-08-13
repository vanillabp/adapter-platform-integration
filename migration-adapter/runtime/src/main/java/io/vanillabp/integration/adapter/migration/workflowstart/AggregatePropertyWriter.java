package io.vanillabp.integration.adapter.migration.workflowstart;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.migration.values.ValueConversion;

/**
 * Writes an attribute of a workflow aggregate by reflection: setter
 * (<code>setX(value)</code>) first, then the field - the writing counterpart of the
 * reader embedded BPMS use to evaluate expressions against the aggregate.
 * <p>
 * Used for a workflow the BPMS started on its own: the aggregate's ID and the
 * process variables the BPMN model set are written into the freshly built
 * aggregate.
 */
final class AggregatePropertyWriter {

  private static final Logger log = LoggerFactory.getLogger(AggregatePropertyWriter.class);

  private AggregatePropertyWriter() {
  }

  /**
   * Writes the value if the aggregate has a writable attribute of that name.
   *
   * @param workflowAggregate The aggregate to write to
   * @param propertyName The attribute's name
   * @param value The value to write
   * @param location What is being written, named by a failure message
   * @return Whether the aggregate had such an attribute
   */
  static boolean write(
      final Object workflowAggregate,
      final String propertyName,
      final Object value,
      final String location) {

    final var aggregateClass = workflowAggregate.getClass();
    final var setter = findSetter(aggregateClass, propertyName);
    if (setter != null) {
      setter.trySetAccessible();
      invoke(
          () -> setter
              .invoke(
                  workflowAggregate,
                  ValueConversion.convert(value, setter.getParameterTypes()[0], location)),
          location);
      return true;
    }

    final var field = findField(aggregateClass, propertyName);
    if (field == null) {
      return false;
    }
    if (!field.trySetAccessible()) {
      log
          .debug(
              "The attribute '{}' of '{}' is not accessible - {} is skipped",
              propertyName,
              aggregateClass.getName(),
              location);
      return false;
    }
    invoke(
        () -> field.set(workflowAggregate, ValueConversion.convert(value, field.getType(), location)),
        location);
    return true;

  }

  private static Method findSetter(
      final Class<?> aggregateClass,
      final String propertyName) {

    final var setterName = "set"
        + Character.toUpperCase(propertyName.charAt(0))
        + propertyName.substring(1);
    for (final var method : aggregateClass.getMethods()) {
      if (method.getName().equals(setterName) && (method.getParameterCount() == 1)) {
        return method;
      }
    }
    return null;

  }

  private static Field findField(
      final Class<?> aggregateClass,
      final String propertyName) {

    var current = aggregateClass;
    while ((current != null) && !current.equals(Object.class)) {
      try {
        return current.getDeclaredField(propertyName);
      } catch (final NoSuchFieldException e) {
        current = current.getSuperclass();
      }
    }
    return null;

  }

  @FunctionalInterface
  private interface ReflectiveWrite {

    void run() throws ReflectiveOperationException;

  }

  private static void invoke(
      final ReflectiveWrite write,
      final String location) {

    try {
      write.run();
    } catch (final ReflectiveOperationException e) {
      throw new IllegalStateException("Could not write %s!".formatted(location), e);
    }

  }

}
