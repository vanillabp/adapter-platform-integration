package io.vanillabp.integration.adapter.migration.workflowtask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads an attribute of a workflow aggregate by reflection: getter (<code>getX()</code>),
 * boolean getter (<code>isX()</code>) or field access, in this order. BPMN expressions of
 * embedded BPMS rely on this order. Best practice is getters with a telling name, computed
 * on the fly if needed. They keep the BPMN independent of the data model.
 */
class AggregatePropertyReader {

  private static final Logger log = LoggerFactory.getLogger(AggregatePropertyReader.class);

  private AggregatePropertyReader() {
  }

  /**
   * Whether the aggregate CLASS declares such an attribute. It uses the same order as
   * {@link #read}, but without loading an aggregate. Adapters ask this while they resolve a
   * BPMN expression, to tell an attribute of the workflow aggregate from a name which means
   * something else.
   *
   * @param aggregateClass The workflow aggregate's class
   * @param propertyName The attribute's name
   * @return Whether the class has such a getter, boolean getter or field
   */
  static boolean has(
      final Class<?> aggregateClass,
      final String propertyName) {

    if ((aggregateClass == null) || (propertyName == null) || propertyName.isEmpty()) {
      return false;
    }
    final var capitalized = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);

    try {
      aggregateClass.getMethod("get"
          + capitalized);
      return true;
    } catch (final NoSuchMethodException e) {
      // fall through to the boolean getter
    }
    try {
      aggregateClass.getMethod("is"
          + capitalized);
      return true;
    } catch (final NoSuchMethodException e) {
      // fall through to field access
    }
    var currentClass = aggregateClass;
    while ((currentClass != null) && (currentClass != Object.class)) {
      try {
        currentClass.getDeclaredField(propertyName);
        return true;
      } catch (final NoSuchFieldException e) {
        currentClass = currentClass.getSuperclass();
      }
    }
    return false;

  }

  static Object read(
      final Object workflowAggregate,
      final String propertyName) {

    final var aggregateClass = workflowAggregate.getClass();
    final var capitalized = Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);

    try {
      final var getter = aggregateClass.getMethod("get"
          + capitalized);
      getter.trySetAccessible();
      return getter.invoke(workflowAggregate);
    } catch (final NoSuchMethodException e) {
      // fall through to the boolean getter
    } catch (final Exception e) {
      log.warn("Could not access '{}#get{}'", aggregateClass.getName(), capitalized, e);
      return null;
    }

    try {
      final var booleanGetter = aggregateClass.getMethod("is"
          + capitalized);
      booleanGetter.trySetAccessible();
      return booleanGetter.invoke(workflowAggregate);
    } catch (final NoSuchMethodException e) {
      // fall through to field access
    } catch (final Exception e) {
      log.warn("Could not access '{}#is{}'", aggregateClass.getName(), capitalized, e);
      return null;
    }

    // the field is looked up along the class hierarchy, exactly like has() does it. An
    // aggregate which inherits its attributes from a base entity is the common case.
    // Answering 'the attribute exists' and then reading it as null would leave the BPMN
    // expression with a silent null
    var currentClass = aggregateClass;
    while ((currentClass != null) && (currentClass != Object.class)) {
      try {
        final var field = currentClass.getDeclaredField(propertyName);
        field.setAccessible(true);
        return field.get(workflowAggregate);
      } catch (final NoSuchFieldException e) {
        currentClass = currentClass.getSuperclass();
      } catch (final Exception e) {
        log.warn("Could not access field '{}' of '{}'", propertyName, aggregateClass.getName(), e);
        return null;
      }
    }
    return null;

  }

}
