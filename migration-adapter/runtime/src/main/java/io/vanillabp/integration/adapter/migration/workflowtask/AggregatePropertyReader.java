package io.vanillabp.integration.adapter.migration.workflowtask;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads an attribute of a workflow aggregate by reflection: getter
 * (<code>getX()</code>), boolean getter (<code>isX()</code>) or field access, in
 * this order - the resolution order BPMN expressions of embedded BPMS rely on
 * (best practice: intention-revealing getters decouple the BPMN from the data
 * model, computed on the fly if needed).
 */
class AggregatePropertyReader {

  private static final Logger log = LoggerFactory.getLogger(AggregatePropertyReader.class);

  private AggregatePropertyReader() {
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

    try {
      final var field = aggregateClass.getDeclaredField(propertyName);
      field.setAccessible(true);
      return field.get(workflowAggregate);
    } catch (final NoSuchFieldException e) {
      return null;
    } catch (final Exception e) {
      log.warn("Could not access field '{}' of '{}'", propertyName, aggregateClass.getName(), e);
      return null;
    }

  }

}
