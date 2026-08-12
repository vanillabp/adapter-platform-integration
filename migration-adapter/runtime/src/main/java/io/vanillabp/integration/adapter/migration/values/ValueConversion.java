package io.vanillabp.integration.adapter.migration.values;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * Converts a value a BPMS reported (a process variable, an input mapping) into the
 * type the application expects. Shared by everything binding BPMS values to Java:
 * the parameters of a <code>&#64;WorkflowTask</code> method, the parameters of a
 * <code>&#64;WorkflowStartedByBpms</code> method and the attributes of a workflow
 * aggregate built for a BPMS-initiated start.
 * <p>
 * Deliberately narrow: identity, the String and Number conversions between the
 * types a BPMS can carry, and a guiding failure for everything else. Anything
 * richer belongs to the application, which knows what its values mean.
 */
public final class ValueConversion {

  private ValueConversion() {
  }

  /**
   * Converts a value supplied by a BPMS to the target type: assignable values pass
   * through, Strings become the common primitive/wrapper types and
   * BigDecimal/BigInteger, numbers are narrowed or widened between number types.
   *
   * @param value The value reported by the BPMS (may be <code>null</code>)
   * @param targetType The type the application expects
   * @param location What is being bound, named by the failure message
   * @return The converted value
   * @throws IllegalStateException If the value cannot be converted, or if it is
   *           <code>null</code> and the target type is primitive
   */
  public static Object convert(
      final Object value,
      final Class<?> targetType,
      final String location) {

    if (value == null) {
      if (targetType.isPrimitive()) {
        throw new IllegalStateException(
            """
                The value bound to %s is null but the parameter's type is primitive! Use the \
                wrapper type or ensure the BPMN input mapping provides a value."""
                .formatted(location));
      }
      return null;
    }
    if (wrapperOf(targetType).isInstance(value)) {
      return value;
    }
    final var target = wrapperOf(targetType);
    if (value instanceof String string) {
      if (target.equals(Integer.class)) {
        return Integer.valueOf(string);
      }
      if (target.equals(Long.class)) {
        return Long.valueOf(string);
      }
      if (target.equals(Double.class)) {
        return Double.valueOf(string);
      }
      if (target.equals(Float.class)) {
        return Float.valueOf(string);
      }
      if (target.equals(Short.class)) {
        return Short.valueOf(string);
      }
      if (target.equals(Byte.class)) {
        return Byte.valueOf(string);
      }
      if (target.equals(Boolean.class)) {
        return Boolean.valueOf(string);
      }
      if (target.equals(BigDecimal.class)) {
        return new BigDecimal(string);
      }
      if (target.equals(BigInteger.class)) {
        return new BigInteger(string);
      }
    }
    if (value instanceof Number number) {
      if (target.equals(Integer.class)) {
        return number.intValue();
      }
      if (target.equals(Long.class)) {
        return number.longValue();
      }
      if (target.equals(Double.class)) {
        return number.doubleValue();
      }
      if (target.equals(Float.class)) {
        return number.floatValue();
      }
      if (target.equals(Short.class)) {
        return number.shortValue();
      }
      if (target.equals(Byte.class)) {
        return number.byteValue();
      }
      if (target.equals(BigDecimal.class)) {
        return new BigDecimal(number.toString());
      }
      if (target.equals(BigInteger.class)) {
        return new BigInteger(number.toString());
      }
      if (target.equals(String.class)) {
        return number.toString();
      }
    }
    throw new IllegalStateException(
        """
            The value of type '%s' bound to %s cannot be converted to the parameter's type '%s'!"""
            .formatted(value.getClass().getName(), location, targetType.getName()));

  }

  /**
   * @param type A type which may be primitive
   * @return Its wrapper type, or the type itself if it is not primitive
   */
  private static Class<?> wrapperOf(
      final Class<?> type) {

    if (!type.isPrimitive()) {
      return type;
    }
    if (type.equals(int.class)) {
      return Integer.class;
    }
    if (type.equals(long.class)) {
      return Long.class;
    }
    if (type.equals(double.class)) {
      return Double.class;
    }
    if (type.equals(float.class)) {
      return Float.class;
    }
    if (type.equals(short.class)) {
      return Short.class;
    }
    if (type.equals(byte.class)) {
      return Byte.class;
    }
    if (type.equals(boolean.class)) {
      return Boolean.class;
    }
    if (type.equals(char.class)) {
      return Character.class;
    }
    return type;

  }

}
