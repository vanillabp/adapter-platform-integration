package io.vanillabp.integration.adapter.migration.values;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Converts a value which a BPMS reported (a process variable, an input mapping) into the
 * type the application expects. Everything which binds BPMS values to Java uses it: the
 * parameters of a <code>&#64;WorkflowTask</code> method, the parameters of a
 * <code>&#64;WorkflowStartedByBpms</code> method, and the attributes of a workflow
 * aggregate built for a start by the BPMS.
 * <p>
 * It is narrow on purpose. It does identity, the String and Number conversions between the
 * types a BPMS can carry, and a guiding failure for everything else. Anything richer
 * belongs to the application, which knows what its values mean.
 * <p>
 * What converts, and what is refused:
 * <ul>
 * <li>A value which already fits the target type passes through. For a primitive target,
 * the value must fit its wrapper. So <code>Object</code> takes whatever the BPMS sent. That
 * is the way to see a value which VanillaBP would refuse to convert.</li>
 * <li><code>null</code> passes through. For a primitive target it fails, because there is
 * no number to write.</li>
 * <li>A number becomes any other number type, and so does the text of a number. Both go
 * through the decimal form of the value. The result is handed over only where it reads
 * back as the same number. So <code>BigDecimal("120.50")</code> becomes a
 * <code>Double</code> of <code>120.5</code>. The same value bound to an <code>int</code>
 * fails, instead of arriving as <code>120</code>.</li>
 * <li>A number becomes a <code>String</code>, and a text becomes a
 * <code>Boolean</code>.</li>
 * <li>The platform shares some values AS text. Their text becomes the value again: an enum
 * from the name of its constant, and every type of {@link TextValueTypes} from the text it
 * travels as. This is the way back for what {@code AggregateSyncSupport} wrote on the way
 * out. So the forms read here are exactly the forms written there.</li>
 * <li>Everything else fails, and the message names what was bound and to what. A type of
 * the application's own is such a case. A BPMS carries plain values, and the application
 * builds what its own types mean. A {@link java.util.Calendar} and a
 * {@link java.util.Locale} fail as well, although the platform writes them out as text.
 * That text does not carry the value back, and the message says which type to declare
 * instead. Decision 57 in the repository's DECISIONS.md has what was measured about those
 * two. Decision 58 says why a {@link java.util.Date} is no longer among them.</li>
 * </ul>
 * Why does a value which does not fit fail, instead of being cut down to size? Otherwise
 * the handler would get a number which nobody wrote, without a word, and act on it. The
 * failure ends the call. So the BPMS raises its incident, and somebody reads the message.
 * This is the rule of {@code AggregateIdRoundTrip} applied to a second value. See decision
 * 55 in the repository's DECISIONS.md.
 */
public final class ValueConversion {

  /**
   * How the decimal form of a value is read as each number type this class converts into.
   * Each of them may lose something. That is why the caller reads the result back before it
   * hands it over.
   */
  private static final Map<Class<?>, Function<BigDecimal, Number>> NUMBER_TYPES = Map
      .of(
          BigDecimal.class, decimal -> decimal,
          BigInteger.class, BigDecimal::toBigInteger,
          Integer.class, BigDecimal::intValue,
          Long.class, BigDecimal::longValue,
          Double.class, BigDecimal::doubleValue,
          Float.class, BigDecimal::floatValue,
          Short.class, BigDecimal::shortValue,
          Byte.class, BigDecimal::byteValue);

  private ValueConversion() {
  }

  /**
   * Converts a value supplied by a BPMS to the target type. A value which fits passes
   * through. A number and the text of a number become any other number type, where the value
   * survives. A number becomes a String, and a text becomes a Boolean. The
   * text of an enum, a UUID, a <code>java.time</code> value, a Date or a TimeZone becomes
   * that value again.
   *
   * @param value The value reported by the BPMS (may be <code>null</code>)
   * @param targetType The type the application expects
   * @param location What is being bound, named by the failure message
   * @return The converted value
   * @throws IllegalStateException If the value cannot be converted, if the conversion
   *           would change the number, or if the value is <code>null</code> and the
   *           target type is primitive
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

    final var target = wrapperOf(targetType);
    if (target.isInstance(value)) {
      return value;
    }
    if ((value instanceof String string) && target.equals(Boolean.class)) {
      return Boolean.valueOf(string);
    }
    if ((value instanceof Number number) && target.equals(String.class)) {
      return number.toString();
    }
    if ((value instanceof String) || (value instanceof Number)) {
      final var converted = asNumber(value, target, targetType, location);
      if (converted != null) {
        return converted;
      }
    }
    if (value instanceof String text) {
      final var converted = fromText(text, target, targetType, location);
      if (converted != null) {
        return converted;
      }
    }

    throw new IllegalStateException(
        """
            The value of type '%s' bound to %s cannot be converted to the parameter's type '%s'!"""
            .formatted(value.getClass().getName(), location, targetType.getName()));

  }

  /**
   * Converts a number, or the text of one, into another number type. The value goes through
   * its decimal form and not through <code>intValue()</code> and its siblings. Those work on
   * the bits and cut a value down without saying so. The result is read back as a decimal
   * and handed over only where it is still the same number. The comparison is numeric, so a
   * trailing zero which the target type cannot keep is no reason to refuse.
   *
   * @param value The number, or the text of one
   * @param target The target type, already unwrapped from a primitive
   * @param targetType The target type as the application declared it, named by a message
   * @param location What is being bound, named by a message
   * @return The converted value, or <code>null</code> if the target is no number type
   * @throws IllegalStateException If the value is no number, or if the conversion would
   *           change it
   */
  private static Object asNumber(
      final Object value,
      final Class<?> target,
      final Class<?> targetType,
      final String location) {

    final var readAsTargetType = NUMBER_TYPES.get(target);
    if (readAsTargetType == null) {
      return null;
    }

    final var decimal = decimalFormOf(value.toString());
    if (decimal == null) {
      throw new IllegalStateException(
          """
              The value '%s' of type '%s' bound to %s is no number, so it cannot be converted to \
              the parameter's type '%s'! Map a number, or declare the parameter as a String."""
              .formatted(value, value.getClass().getName(), location, targetType.getName()));
    }

    final var converted = readAsTargetType.apply(decimal);
    final var readBack = decimalFormOf(converted.toString());
    if ((readBack == null) || (readBack.compareTo(decimal) != 0)) {
      throw new IllegalStateException(
          """
              The value '%s' of type '%s' bound to %s does not fit the parameter's type '%s', \
              which would hold '%s' instead! Declare a type the value fits into, or map a value \
              the type can hold."""
              .formatted(value, value.getClass().getName(), location, targetType.getName(), converted));
    }
    return converted;

  }

  /**
   * @param text The text of a number. For a Number this is its own <code>toString()</code>,
   *          the shortest text which reads back as the same value. So a <code>Float</code>
   *          of <code>0.1f</code> is <code>0.1</code>, not the double it widens to
   * @return The value as a decimal, or <code>null</code> if the text is no number at all.
   *         Infinity and not-a-number are no numbers either
   */
  private static BigDecimal decimalFormOf(
      final String text) {

    try {
      return new BigDecimal(text);
    } catch (final NumberFormatException e) {
      return null;
    }

  }

  /**
   * Reads a value back from the text which the way out wrote for it. The platform shares an
   * enum as its name. It shares a value type like a {@link UUID} or a {@link LocalDate} as
   * the text {@link TextValueTypes} names for it. So the way back reads exactly those forms
   * and nothing else. A text of another shape is refused, not guessed at.
   *
   * @param text The text the BPMS reported
   * @param target The target type, already unwrapped from a primitive
   * @param targetType The target type as the application declared it, named by a message
   * @param location What is being bound, named by a message
   * @return The value, or <code>null</code> if the target is no type travelling as text
   * @throws IllegalStateException If the target travels as text but this text is none of
   *           its own, or if the target is one whose text cannot be trusted
   */
  private static Object fromText(
      final String text,
      final Class<?> target,
      final Class<?> targetType,
      final String location) {

    if (target.isEnum()) {
      return constantOf(text, target, targetType, location);
    }

    if (TextValueTypes.isKnownToCarryNothingBack(target)) {
      throw new IllegalStateException(
          """
              The value '%s' bound to %s cannot be converted to the parameter's type '%s'! %s"""
              .formatted(text, location, targetType.getName(), TextValueTypes.adviceFor(target)));
    }

    final var howItTravels = TextValueTypes.ofDeclaredType(target);
    if (howItTravels == null) {
      return null;
    }
    try {
      return howItTravels.read().apply(text);
    } catch (final RuntimeException e) {
      throw new IllegalStateException(
          """
              The value '%s' bound to %s is no '%s'! A value of that type travels as text like \
              '%s'. Map a value in that form, or declare the parameter as a String and read it in \
              your own code."""
              .formatted(text, location, targetType.getName(), howItTravels.example()), e);
    }

  }

  /**
   * @param text The constant's name, because the way out shares an enum as its name
   * @param target The enum type
   * @param targetType The target type as the application declared it, named by a message
   * @param location What is being bound, named by a message
   * @return The constant of that name
   * @throws IllegalStateException If the enum has no constant of that name. The message
   *           names the constants it has. Otherwise a handler which reads a name the model
   *           invented would act on a value nobody declared, or on none at all.
   */
  private static Object constantOf(
      final String text,
      final Class<?> target,
      final Class<?> targetType,
      final String location) {

    for (final var constant : target.getEnumConstants()) {
      if (((Enum<?>) constant).name().equals(text)) {
        return constant;
      }
    }
    throw new IllegalStateException(
        """
            The value '%s' bound to %s is no constant of the enum '%s', which knows %s! An enum is \
            shared with the BPMS as the name of its constant, so map one of those names, or \
            declare the parameter as a String to see whatever the model produced."""
            .formatted(
                text,
                location,
                targetType.getName(),
                Arrays
                    .stream(target.getEnumConstants())
                    .map(constant -> "'"
                        + ((Enum<?>) constant).name()
                        + "'")
                    .collect(Collectors.joining(", "))));

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
