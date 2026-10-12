package io.vanillabp.integration.adapter.migration.values;

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
import java.util.Calendar;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Function;

/**
 * The types for which a value of a workflow aggregate travels as TEXT, and the text each
 * of them travels as. One list serves both directions. {@code AggregateSyncSupport} writes
 * the text on the way out, and {@link ValueConversion} reads it on the way back. So a type
 * is carried both ways or not at all. A change to one half is a change to this list.
 * <p>
 * The list is a deliberate SELECTION, not all value types of the JDK. It has what a
 * workflow aggregate holds often enough to be worth carrying: an identifier, a point in
 * time, a length of time, a zone. Somebody who misses a type opens an issue for it. Then it
 * is added here with the text it travels as.
 * <p>
 * Enums are carried as well, but they are not in the list. There is nothing to write down
 * per enum, because a constant travels as its name.
 * <p>
 * Decision 58 in the repository's DECISIONS.md says why there is a list instead of a check
 * on the package of a class.
 */
public final class TextValueTypes {

  /**
   * How one type travels as text: the text which a value of it is shared as, how that text
   * is read back, and an example text for the message about a wrong one.
   *
   * @param write The text the BPMS is given for a value of this type
   * @param read The value that text is read back as
   * @param example One text of that form
   */
  public record TextForm(
                         Function<Object, String> write,
                         Function<String, Object> read,
                         String example) {
  }

  /**
   * The types carried as text. The order decides which entry answers for a value of a
   * SUBCLASS. So a subtype comes before the type it extends.
   */
  private static final Map<Class<?>, TextForm> TYPES = types();

  /**
   * Types which the way out writes as text but the way back cannot read. Each has the
   * advice a developer needs. They are not refused like other types, because their text in
   * the BPMS looks readable. So somebody has to say why it is not read.
   */
  private static final Map<Class<?>, String> NOT_CARRIED = notCarried();

  /**
   * What is said about a type neither list names.
   */
  private static final String SHARE_A_TYPE_WHICH_TRAVELS = """
      A value of that type is shared with the BPMS as the text it writes itself. Share a String \
      and read it in your own code, or share one of the types VanillaBP carries both ways: an \
      enum, a UUID, a java.time value, a Date or a TimeZone.""";

  private TextValueTypes() {
  }

  private static Map<Class<?>, TextForm> types() {

    final Map<Class<?>, TextForm> types = new LinkedHashMap<>();
    types.put(UUID.class, textOf(UUID::fromString, "f81d4fae-7dec-11d0-a765-00a0c91e6bf6"));
    types.put(Instant.class, textOf(Instant::parse, "2026-09-16T18:15:30Z"));
    types.put(LocalDate.class, textOf(LocalDate::parse, "2026-09-16"));
    types.put(LocalTime.class, textOf(LocalTime::parse, "20:15:30"));
    types.put(LocalDateTime.class, textOf(LocalDateTime::parse, "2026-09-16T20:15:30"));
    types.put(OffsetDateTime.class, textOf(OffsetDateTime::parse, "2026-09-16T20:15:30+02:00"));
    types.put(OffsetTime.class, textOf(OffsetTime::parse, "20:15:30+02:00"));
    types.put(ZonedDateTime.class, textOf(ZonedDateTime::parse, "2026-09-16T20:15:30+02:00[Europe/Berlin]"));
    types.put(Year.class, textOf(Year::parse, "2026"));
    types.put(YearMonth.class, textOf(YearMonth::parse, "2026-09"));
    types.put(MonthDay.class, textOf(MonthDay::parse, "--09-16"));
    types.put(Duration.class, textOf(Duration::parse, "PT1H30M"));
    types.put(Period.class, textOf(Period::parse, "P3Y6M4D"));
    // an offset IS a zone, so it has to answer before the zone does
    types.put(ZoneOffset.class, textOf(ZoneOffset::of, "+02:00"));
    types.put(ZoneId.class, textOf(ZoneId::of, "Europe/Berlin"));
    // the text of these two types says less than the value holds. So the text is built
    // and not taken from the value. The class comment of ValueConversion says more
    types
        .put(
            Date.class,
            new TextForm(
                value -> Instant.ofEpochMilli(((Date) value).getTime()).toString(), text -> Date
                    .from(Instant.parse(text)), "2026-09-16T18:15:30.123Z"));
    types
        .put(
            TimeZone.class,
            new TextForm(
                value -> ((TimeZone) value).toZoneId().getId(),
                // through ZoneId, because TimeZone.getTimeZone(String) answers GMT for any
                // text it does not know, and a wrong zone would travel unnoticed
                text -> TimeZone.getTimeZone(ZoneId.of(text)), "Europe/Berlin"));
    return java.util.Collections.unmodifiableMap(types);

  }

  /**
   * @param read How the text is read back
   * @param example One text of that form
   * @return A form whose text is the one the type writes itself
   */
  private static TextForm textOf(
      final Function<String, Object> read,
      final String example) {

    return new TextForm(String::valueOf, read, example);

  }

  private static Map<Class<?>, String> notCarried() {

    final Map<Class<?>, String> types = new LinkedHashMap<>();
    types
        .put(
            Calendar.class,
            """
                A Calendar is shared with the BPMS as its debug form, several hundred characters \
                naming every field of the implementation. Share an Instant for the point in time \
                it holds, and a TimeZone or a ZoneId next to it where the zone matters too.""");
    types
        .put(
            Locale.class,
            """
                A Locale is shared with the BPMS as text like 'de_DE', which no Locale method reads \
                back, and Locale.ROOT is shared as an empty text. Share a String and build the \
                Locale in your own code.""");
    return java.util.Collections.unmodifiableMap(types);

  }

  /**
   * The form in which a VALUE travels, asked about the real class of the value. That class
   * is often not the type the application declared. A zone is a {@code java.time.ZoneRegion},
   * a time zone is a {@code sun.util.calendar.ZoneInfo}. An attribute declared as
   * {@code Date} holds a {@code java.sql.Timestamp} wherever a JPA provider filled it. So
   * the question here is which of the carried types the value IS. Then every value of a
   * type reaches the BPMS as the same text, whichever class it has.
   *
   * @param valueClass The class of the value
   * @return The form, or <code>null</code> if values of that class travel no text of this
   *         list
   */
  public static TextForm ofValue(
      final Class<?> valueClass) {

    final var sameClass = TYPES.get(valueClass);
    if (sameClass != null) {
      return sameClass;
    }
    return TYPES
        .entrySet()
        .stream()
        .filter(type -> type.getKey().isAssignableFrom(valueClass))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(null);

  }

  /**
   * The form in which a DECLARED type travels. The type must match exactly, a subclass does
   * not count. The text is promised for the types named here, and a subclass may write and
   * read something else.
   *
   * @param declaredType The type the application declared
   * @return The form, or <code>null</code> if that type is carried as no text
   */
  public static TextForm ofDeclaredType(
      final Class<?> declaredType) {

    return TYPES.get(declaredType);

  }

  /**
   * What to declare instead of a type whose text carries no value back.
   *
   * @param type The declared type
   * @return The advice, naming that type where there is something to say about it
   */
  public static String adviceFor(
      final Class<?> type) {

    return NOT_CARRIED
        .entrySet()
        .stream()
        .filter(entry -> entry.getKey().isAssignableFrom(type))
        .map(Map.Entry::getValue)
        .findFirst()
        .orElse(SHARE_A_TYPE_WHICH_TRAVELS);

  }

  /**
   * Whether an attribute of that type stops the boot instead of being shared with the BPMS.
   * {@link Calendar} is the only such type. Its text is the debug form of the
   * implementation, several hundred characters which name every field. No model can read a
   * point in time from it, and no operator understands what they see. Every other type
   * whose text carries no value back at least writes something a person can read. Those get
   * a word at startup and not a refusal.
   * <p>
   * Decision 59 in the repository's DECISIONS.md says why this one type is refused and the
   * others are not.
   *
   * @param type The declared type of an attribute, or of an element of one
   * @return Whether an attribute of that type may not reach the BPMS
   */
  public static boolean isRefusedOnTheWayOut(
      final Class<?> type) {

    return Calendar.class.isAssignableFrom(type);

  }

  /**
   * Whether the way back can say why a text cannot become a value of that type.
   * <p>
   * A parameter of such a type is refused with the advice which belongs to the type
   * ({@link #adviceFor}), not with a conversion error. The text did arrive. What the
   * developer needs to hear is that this type never travels back.
   *
   * @param type The declared type
   * @return Whether something is known about why this type's text carries no value back
   */
  public static boolean isKnownToCarryNothingBack(
      final Class<?> type) {

    return NOT_CARRIED.keySet().stream().anyMatch(known -> known.isAssignableFrom(type));

  }

}
