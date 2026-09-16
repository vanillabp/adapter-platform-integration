package io.vanillabp.integration.adapter.migration.values;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
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
 * from the name of its constant, a {@link java.util.UUID}, and the
 * <code>java.time</code> values from the form each of them writes itself. This is the way
 * back for what {@code AggregateSyncSupport} wrote on the way out, so the forms read here
 * are the forms written there and no others.</li>
 * <li>Everything else fails, naming what was bound and what it was bound to. A type of
 * the application's own is such a case: a BPMS carries plain values, and what one of the
 * application's types means is the application's to build. A {@link java.util.Date}, a
 * {@link java.util.Calendar} and a {@link java.util.Locale} fail as well although the
 * platform writes them out as text, because that text does not carry the value back; the
 * message says which type to declare instead. What was measured about those three is in
 * decision 57 in the repository's DECISIONS.md.</li>
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

  /**
   * One type travelling as text: how its own text form is read back, and an example of
   * that form for the message a wrong text earns.
   */
  private record TextForm(
                          Function<String, Object> readBack,
                          String example) {
  }

  /**
   * The types the way out shares as text, and the way back for each of them. The way out
   * writes the string form the type itself produces ({@code AggregateSyncSupport}), and
   * every entry here reads exactly that form back as the same value. Nothing else is
   * added: a text a type does not write is a text this cannot promise anything about.
   */
  private static final Map<Class<?>, TextForm> TEXT_TYPES = textTypes();

  /**
   * Types whose text form the way out writes and the way back cannot trust, each with the
   * advice the developer needs. Kept apart from a bare refusal because the text in the
   * BPMS looks readable, so somebody has to say why it is not read.
   */
  private static final Map<Class<?>, String> UNREADABLE_TEXT_TYPES = unreadableTextTypes();

  private ValueConversion() {
  }

  private static Map<Class<?>, TextForm> textTypes() {

    final Map<Class<?>, TextForm> types = new LinkedHashMap<>();
    types.put(UUID.class, new TextForm(UUID::fromString, "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"));
    types.put(Instant.class, new TextForm(Instant::parse, "2026-09-16T18:15:30Z"));
    types.put(LocalDate.class, new TextForm(LocalDate::parse, "2026-09-16"));
    types.put(LocalTime.class, new TextForm(LocalTime::parse, "20:15:30"));
    types.put(LocalDateTime.class, new TextForm(LocalDateTime::parse, "2026-09-16T20:15:30"));
    types.put(OffsetDateTime.class, new TextForm(OffsetDateTime::parse, "2026-09-16T20:15:30+02:00"));
    types.put(OffsetTime.class, new TextForm(OffsetTime::parse, "20:15:30+02:00"));
    types.put(ZonedDateTime.class, new TextForm(ZonedDateTime::parse, "2026-09-16T20:15:30+02:00[Europe/Berlin]"));
    types.put(Year.class, new TextForm(Year::parse, "2026"));
    types.put(YearMonth.class, new TextForm(YearMonth::parse, "2026-09"));
    types.put(MonthDay.class, new TextForm(MonthDay::parse, "--09-16"));
    types.put(Duration.class, new TextForm(Duration::parse, "PT1H30M"));
    types.put(Period.class, new TextForm(Period::parse, "P3Y6M4D"));
    types.put(ZoneId.class, new TextForm(ZoneId::of, "Europe/Berlin"));
    types.put(ZoneOffset.class, new TextForm(ZoneOffset::of, "+02:00"));
    return Map.copyOf(types);

  }

  private static Map<Class<?>, String> unreadableTextTypes() {

    final Map<Class<?>, String> types = new LinkedHashMap<>();
    final var insteadOfADate = """
        A Date is shared with the BPMS as text like 'Wed Sep 16 21:55:30 CEST 2026', which \
        drops the milliseconds and names the time zone by an abbreviation several zones \
        share, so reading it back can move the point in time. Declare an Instant, an \
        OffsetDateTime or a ZonedDateTime instead, in the parameter and in the workflow \
        aggregate.""";
    types.put(Date.class, insteadOfADate);
    types.put(Calendar.class, insteadOfADate);
    types
        .put(
            Locale.class,
            """
                A Locale is shared with the BPMS as text like 'de_DE', which no Locale method reads \
                back, and Locale.ROOT is shared as an empty text. Declare the parameter as a String \
                and build the Locale in your own code.""");
    return Map.copyOf(types);

  }

  /**
   * Converts a value supplied by a BPMS to the target type: assignable values pass
   * through, a number and the text of a number become any other number type where the
   * value survives, a number becomes a String and the text of one becomes a Boolean, and
   * the text of an enum, a UUID or a <code>java.time</code> value becomes that value
   * again.
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
   * string form the type itself writes, so the way back reads exactly those forms and
   * nothing it invented. A text a type does not write is refused rather than guessed at.
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

    final var whatToDeclareInstead = UNREADABLE_TEXT_TYPES.get(target);
    if (whatToDeclareInstead != null) {
      throw new IllegalStateException(
          """
              The value '%s' bound to %s cannot be converted to the parameter's type '%s'! %s"""
              .formatted(text, location, targetType.getName(), whatToDeclareInstead));
    }

    final var howItIsWritten = TEXT_TYPES.get(target);
    if (howItIsWritten == null) {
      return null;
    }
    try {
      return howItIsWritten.readBack().apply(text);
    } catch (final RuntimeException e) {
      throw new IllegalStateException(
          """
              The value '%s' bound to %s is no '%s'! A value of that type travels as the text it \
              writes itself, like '%s'. Map a value in that form, or declare the parameter as a \
              String and read it in your own code."""
              .formatted(text, location, targetType.getName(), howItIsWritten.example()), e);
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
