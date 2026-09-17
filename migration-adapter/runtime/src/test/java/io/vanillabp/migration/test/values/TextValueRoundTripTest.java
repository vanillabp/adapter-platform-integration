package io.vanillabp.migration.test.values;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.sync.AggregateSyncSupport;
import io.vanillabp.integration.adapter.migration.values.ValueConversion;
import io.vanillabp.integration.adapter.spi.AggregateSyncMode;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The way out and the way back, measured against each other: an aggregate shares a value
 * with the BPMS, and a handler declares the type it was shared from. The way out is
 * {@link AggregateSyncSupport}, which writes an enum as the name of its constant and a
 * value type as the string form the type itself writes, and the way back is
 * {@link ValueConversion}, which reads exactly those forms.
 * <p>
 * The texts are asserted as well as the values, because the text is what an operator
 * reads in the BPMS and what a BPMN expression works on. A form which changed would be a
 * change to the model side, not only to this conversion.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TextValueRoundTripTest {

  public enum Decision {
    APPROVED,
    REJECTED
  }

  /**
   * One attribute of a workflow aggregate, which is all the way out needs to walk.
   */
  public static class Holder {

    private final Object value;

    public Holder(
        final Object value) {
      this.value = value;
    }

    public Object getValue() {
      return value;
    }

  }

  private static final AggregateSyncSupport WAY_OUT = new AggregateSyncSupport();

  /**
   * @return What the BPMS is given for that value
   */
  private static Object shared(
      final Object value) {

    return WAY_OUT.syncedValues(new Holder(value), AggregateSyncMode.FULL).get("value");

  }

  /**
   * @return What a handler declaring the value's own type receives back
   */
  private static Object handedBack(
      final Object value) {

    return ValueConversion.convert(shared(value), value.getClass(), "the parameter");

  }

  @Test
  @DisplayName("Every value shared as text is shared as the text its type travels as")
  public void theTextsSharedWithTheBpms() {

    final var texts = new LinkedHashMap<Object, String>();
    texts.put(UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6"), "f81d4fae-7dec-11d0-a765-00a0c91e6bf6");
    texts.put(Decision.APPROVED, "APPROVED");
    texts.put(LocalDate.parse("2026-09-16"), "2026-09-16");
    texts.put(LocalTime.parse("20:15:30"), "20:15:30");
    texts.put(LocalDateTime.parse("2026-09-16T20:15:30"), "2026-09-16T20:15:30");
    texts.put(OffsetDateTime.parse("2026-09-16T20:15:30+02:00"), "2026-09-16T20:15:30+02:00");
    texts.put(OffsetTime.parse("20:15:30+02:00"), "20:15:30+02:00");
    texts
        .put(
            ZonedDateTime.parse("2026-09-16T20:15:30+02:00[Europe/Berlin]"),
            "2026-09-16T20:15:30+02:00[Europe/Berlin]");
    texts.put(Instant.parse("2026-09-16T18:15:30Z"), "2026-09-16T18:15:30Z");
    texts.put(Year.of(2026), "2026");
    texts.put(YearMonth.of(2026, 9), "2026-09");
    texts.put(MonthDay.of(9, 16), "--09-16");
    texts.put(Duration.ofMinutes(90), "PT1H30M");
    texts.put(Period.of(3, 6, 4), "P3Y6M4D");
    texts.put(ZoneId.of("Europe/Berlin"), "Europe/Berlin");
    texts.put(ZoneOffset.ofHours(2), "+02:00");
    texts.put(TimeZone.getTimeZone("Europe/Berlin"), "Europe/Berlin");
    texts.put(new Date(1789588530123L), "2026-09-16T19:55:30.123Z");

    texts.forEach((
        value,
        text) -> assertEquals(text, shared(value), value.getClass().getName()));

  }

  @Test
  @DisplayName("What the way out wrote as text the way back reads as the same value")
  public void everyTextSharedComesBackAsItsValue() {

    final Map<String, Object> values = Map
        .ofEntries(
            Map.entry("a UUID", UUID.fromString("f81d4fae-7dec-11d0-a765-00a0c91e6bf6")),
            Map.entry("an enum constant", Decision.APPROVED),
            Map.entry("a date", LocalDate.parse("2026-09-16")),
            Map.entry("a time", LocalTime.of(20, 15, 30, 123456789)),
            Map.entry("a local timestamp", LocalDateTime.parse("2026-09-16T20:15:30")),
            Map.entry("a timestamp with an offset", OffsetDateTime.parse("2026-09-16T20:15:30+02:00")),
            Map.entry("a time with an offset", OffsetTime.parse("20:15:30+02:00")),
            Map.entry("a timestamp with a zone", ZonedDateTime.parse("2026-09-16T20:15:30+02:00[Europe/Berlin]")),
            Map.entry("an instant", Instant.parse("2026-09-16T18:15:30Z")),
            Map.entry("a year", Year.of(2026)),
            Map.entry("a month of a year", YearMonth.of(2026, 9)),
            Map.entry("a day of a month", MonthDay.of(9, 16)),
            Map.entry("a duration", Duration.ofMinutes(90)),
            Map.entry("a negative duration", Duration.ofSeconds(-30)),
            Map.entry("a duration of no time at all", Duration.ZERO),
            Map.entry("a period", Period.of(3, 6, 4)),
            Map.entry("a period of no time at all", Period.ZERO),
            Map.entry("an offset", ZoneOffset.ofHours(2)),
            Map.entry("a day of the week", java.time.DayOfWeek.FRIDAY),
            Map.entry("a month", java.time.Month.SEPTEMBER),
            Map.entry("a date with milliseconds", new Date(1789588530123L)),
            Map.entry("a date before 1970", new Date(-12345678901234L)));

    values.forEach((
        what,
        value) -> assertEquals(value, handedBack(value), what));

    // a zone and a time zone are the values whose runtime class is not the type an
    // application declares, so the declared type is named here rather than read off the
    // value
    final var zone = ZoneId.of("Europe/Berlin");
    assertEquals(zone, ValueConversion.convert(shared(zone), ZoneId.class, "the parameter"), "a zone");

    final var timeZone = TimeZone.getTimeZone("Europe/Berlin");
    assertEquals(
        timeZone,
        ValueConversion.convert(shared(timeZone), TimeZone.class, "the parameter"),
        "a time zone");

  }

  @Test
  @DisplayName("A duration keeps its length and loses the way it was written")
  public void aDurationKeepsItsLengthAndLosesItsWording() {

    final var fortnight = Duration.ofDays(14);

    assertEquals("PT336H", shared(fortnight), "java.time writes a duration in hours from a day on");
    assertNotEquals("P14D", shared(fortnight), "so the text in the model is not the text the application wrote");
    assertEquals(fortnight, handedBack(fortnight), "the length is the same, which is what a timer fires on");

  }

  @Test
  @DisplayName("A Date travels as the instant it holds, milliseconds included")
  public void aDateTravelsAsTheInstantItHolds() {

    final var signedAt = new Date(1789588530123L);

    assertEquals("2026-09-16T19:55:30.123Z", shared(signedAt), "the text the BPMS carries");
    assertEquals(signedAt, handedBack(signedAt), "and the same point in time comes back");

    final var wholeSecond = new Date(1789588530000L);
    assertEquals("2026-09-16T19:55:30Z", shared(wholeSecond), "a whole second writes no milliseconds");
    assertEquals(wholeSecond, handedBack(wholeSecond));

  }

  @Test
  @DisplayName("A Date reads the same in every time zone, because it travels as an instant")
  public void aDateDoesNotDependOnTheZoneOfTheServerWritingIt() {

    final var signedAt = new Date(1789588530123L);
    final var zoneOfThisJvm = TimeZone.getDefault();
    try {
      TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"));
      final var writtenInIndia = shared(signedAt);
      TimeZone.setDefault(TimeZone.getTimeZone("Europe/Dublin"));
      final var writtenInIreland = shared(signedAt);

      assertEquals(writtenInIndia, writtenInIreland, "the text does not say which server wrote it");
      assertEquals(signedAt, handedBack(signedAt), "and it reads back to the same point in time");
    } finally {
      TimeZone.setDefault(zoneOfThisJvm);
    }

  }

  @Test
  @DisplayName("A TimeZone travels as the id of its zone, a value whose class is not the declared type")
  public void aTimeZoneTravelsAsTheIdOfItsZone() {

    final var berlin = TimeZone.getTimeZone("Europe/Berlin");

    assertNotEquals(
        TimeZone.class,
        berlin.getClass(),
        "the runtime hands out an implementation of its own, which used to end the sync point");
    assertEquals("Europe/Berlin", shared(berlin), "the text the BPMS carries");
    assertEquals(berlin, ValueConversion.convert(shared(berlin), TimeZone.class, "the parameter"));

  }

  @Test
  @DisplayName("A zone with a fixed offset survives, a three-letter id is replaced by the zone it means")
  public void whatBecomesOfAnIdWhichIsNoZoneName() {

    final var fixedOffset = TimeZone.getTimeZone("GMT+05:30");
    assertEquals("GMT+05:30", shared(fixedOffset), "a fixed offset is a zone id of its own");
    assertEquals(fixedOffset, ValueConversion.convert(shared(fixedOffset), TimeZone.class, "the parameter"));

    // measured on 2026-09-17, Java 21: the three-letter ids are not zone names, and
    // TimeZone.toZoneId() replaces them by the zone they stand for. The rules are the
    // same, the id is not, and an application comparing ids has to know that.
    final var india = TimeZone.getTimeZone("IST");
    assertEquals("Asia/Kolkata", shared(india), "the id of the zone the abbreviation means");

    final var read = (TimeZone) ValueConversion.convert(shared(india), TimeZone.class, "the parameter");
    assertTrue(india.hasSameRules(read), "the same zone");
    assertNotEquals(india.getID(), read.getID(), "under the name the abbreviation stands for");

  }

  @Test
  @DisplayName("A Calendar is shared as its debug form and refused on the way back")
  public void aCalendarIsNoWayToCarryAPointInTime() {

    final var calendar = new GregorianCalendar(TimeZone.getTimeZone("Europe/Berlin"));
    calendar.setTimeInMillis(1789588530123L);

    final var text = (String) shared(calendar);
    assertTrue(text.startsWith(GregorianCalendar.class.getName()
        + "["), text);
    assertTrue(text.length() > 500, "the debug form, several hundred characters long");

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> ValueConversion.convert(text, Calendar.class, "the parameter"));

    assertTrue(exception.getMessage().contains("debug form"), exception::getMessage);
    assertTrue(exception.getMessage().contains("Share an Instant"), exception::getMessage);

  }

  @Test
  @DisplayName("A Locale is refused as well, and says to carry it as a String")
  public void aLocaleIsRefused() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> ValueConversion.convert(shared(Locale.GERMANY), Locale.class, "the parameter"));

    assertEquals("de_DE", shared(Locale.GERMANY), "what the way out writes for a locale");
    assertTrue(exception.getMessage().contains("Share a String"), exception::getMessage);

  }

  @Test
  @DisplayName("An enum constant the model invented is refused, naming the constants there are")
  public void anUnknownEnumConstantIsRefused() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> ValueConversion.convert("ESCALATED", Decision.class, "the parameter"));

    assertTrue(exception.getMessage().contains("'ESCALATED'"), exception::getMessage);
    assertTrue(exception.getMessage().contains(Decision.class.getName()), exception::getMessage);
    assertTrue(exception.getMessage().contains("'APPROVED', 'REJECTED'"), exception::getMessage);

  }

  @Test
  @DisplayName("A text no value of that type would write is refused, with an example of one which would")
  public void aTextTheTypeWouldNeverWriteIsRefused() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> ValueConversion.convert("16.09.2026", LocalDate.class, "the parameter"));

    assertTrue(exception.getMessage().contains("'16.09.2026'"), exception::getMessage);
    assertTrue(exception.getMessage().contains("'2026-09-16'"), exception::getMessage);

  }

  @Test
  @DisplayName("A cycle and a cron expression stay text, which is what a String parameter is for")
  public void aTimerCycleIsNoDuration() {

    assertEquals("R5/PT10S", ValueConversion.convert("R5/PT10S", String.class, "the parameter"));

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> ValueConversion.convert("R5/PT10S", Duration.class, "the parameter"));

    assertTrue(exception.getMessage().contains("'PT1H30M'"), exception::getMessage);

  }

  @Test
  @DisplayName("A type of the application's own stays refused")
  public void anApplicationsOwnTypeStaysRefused() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> ValueConversion.convert("whatever", Holder.class, "the parameter"));

    assertTrue(exception.getMessage().contains("cannot be converted"), exception::getMessage);

  }

}
