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
 * Converts a value a BPMS reported (a process variable, an input mapping) into the
 * type the application expects. Shared by everything binding BPMS values to Java:
 * the parameters of a <code>&#64;WorkflowTask</code> method, the parameters of a
 * <code>&#64;WorkflowStartedByBpms</code> method and the attributes of a workflow
 * aggregate built for a BPMS-initiated start.
 * <p>
 * Deliberately narrow: identity, the String and Number conversions between the
 * types a BPMS can carry, and a guiding failure for everything else. Anything
 * richer belongs to the application, which knows what its values mean.
 * <p>
 * What converts, and what is refused:
 * <ul>
 * <li>A value the target type already holds passes through, a primitive target through
 * its wrapper. <code>Object</code> therefore takes whatever the BPMS sent, which is the
 * way to see a value VanillaBP would refuse to convert.</li>
 * <li><code>null</code> passes through, and against a primitive target it fails, because
 * there is no number to write.</li>
 * <li>A number becomes any other number type, and the text of a number becomes one too.
 * Both go through the decimal form of the value, and the result is handed over only where
 * it reads back as the same number. So <code>BigDecimal("120.50")</code> becomes a
 * <code>Double</code> of <code>120.5</code>, and the same value bound to an
 * <code>int</code> fails instead of arriving as <code>120</code>.</li>
 * <li>A number becomes a <code>String</code>, and the text of one becomes a
 * <code>Boolean</code>.</li>
 * <li>The text of a value the platform shares AS text becomes that value again: an enum
 * from the name of its constant, and every type of {@link TextValueTypes} from the text
 * it travels as. This is the way back for what {@code AggregateSyncSupport} wrote on the
 * way out, so the forms read here are the forms written there and no others.</li>
 * <li>Everything else fails, naming what was bound and what it was bound to. A type of
 * the application's own is such a case: a BPMS carries plain values, and what one of the
 * application's types means is the application's to build. A {@link java.util.Calendar}
 * and a {@link java.util.Locale} fail as well although the platform writes them out as
 * text, because that text does not carry the value back; the message says which type to
 * declare instead. What was measured about those two is in decision 57 in the
 * repository's DECISIONS.md, and why a {@link java.util.Date} is no longer among them is
 * decision 58.</li>
 * </ul>
 * Why a value which does not fit fails rather than being cut down to size: the handler
 * would otherwise receive a number nobody wrote, with nothing said about it, and act on
 * it. The failure ends the invocation, so the BPMS raises its incident and somebody reads
 * the message. This is the rule of {@code AggregateIdRoundTrip} applied to a second
 * value, see decision 55 in the repository's DECISIONS.md.
 */
public final class ValueConversion {

  /**
   * How the decimal form of a value is read as each number type this converts into. Every
   * one of them may lose something, which is why the caller reads the result back before
   * it hands it over.
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
   * Converts a value supplied by a BPMS to the target type: assignable values pass
   * through, a number and the text of a number become any other number type where the
   * value survives, a number becomes a String and the text of one becomes a Boolean, and
   * the text of an enum, a UUID, a <code>java.time</code> value, a Date or a TimeZone
   * becomes that value again.
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
   * Converts a number, or the text of one, into another number type. The value travels
   * through its decimal form rather than through <code>intValue()</code> and its
   * siblings, because those work on the bit pattern and cut a value down without saying
   * so. What comes out is read back as a decimal again and handed over only where it is
   * still the same number, compared numerically, so a trailing zero the target type
   * cannot keep is no reason to refuse.
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
   * @param text The text of a number, which for a Number is its own
   *          <code>toString()</code>: the shortest text reading back as that same value,
   *          so a <code>Float</code> of <code>0.1f</code> is <code>0.1</code> rather than
   *          the double it widens to
   * @return The value as a decimal, or <code>null</code> if the text is no number at all,
   *         which infinity and not-a-number are as well
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
   * Reads a value back from the text the way out wrote for it. The platform shares an
   * enum as its name and a value type like a {@link UUID} or a {@link LocalDate} as the
   * text {@link TextValueTypes} names for it, so the way back reads exactly those forms
   * and nothing it invented. A text of another shape is refused rather than guessed at.
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
   * @param text The constant's name, which is what the way out shares an enum as
   * @param target The enum type
   * @param targetType The target type as the application declared it, named by a message
   * @param location What is being bound, named by a message
   * @return The constant of that name
   * @throws IllegalStateException If the enum has no constant of that name, naming the
   *           ones it has. A handler reading a name the model invented would otherwise
   *           act on a value nobody declared, or on none at all.
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
