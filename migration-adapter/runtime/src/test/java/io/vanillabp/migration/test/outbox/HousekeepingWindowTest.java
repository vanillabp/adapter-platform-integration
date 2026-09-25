package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.outbox.HousekeepingWindow;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The hours of the day an outbox house-keeps in. What is under test is the clock: the
 * window follows the local time of the zone it was configured for, it may cross midnight,
 * and it survives the two nights a year on which a clock moves.
 */
@ExtendWith(SuppressOutputExtension.class)
public class HousekeepingWindowTest {

  private static final ZoneId VIENNA = ZoneId.of("Europe/Vienna");

  private static HousekeepingWindow atNight() {

    return new HousekeepingWindow(LocalTime.of(4, 0), LocalTime.of(5, 0), VIENNA);

  }

  /**
   * A moment written as the local time of the zone under test.
   *
   * @param date The day
   * @param time The local time on it
   * @return That moment
   */
  private static java.time.Instant viennaAt(
      final String date,
      final String time) {

    return LocalDate
        .parse(date)
        .atTime(LocalTime.parse(time))
        .atZone(VIENNA)
        .toInstant();

  }

  @Test
  @DisplayName("The window is open between its two local times and shut outside them")
  public void theWindowFollowsTheLocalClock() {

    final var window = atNight();

    assertTrue(window.isOpenAt(viennaAt("2026-09-25", "04:00")), "the start belongs to the window");
    assertTrue(window.isOpenAt(viennaAt("2026-09-25", "04:59")));
    assertFalse(window.isOpenAt(viennaAt("2026-09-25", "05:00")), "the end does not, so a tick on it closes it");
    assertFalse(window.isOpenAt(viennaAt("2026-09-25", "03:59")));
    assertFalse(window.isOpenAt(viennaAt("2026-09-25", "14:00")));

  }

  @Test
  @DisplayName("A window whose end lies before its start crosses midnight")
  public void aWindowMayCrossMidnight() {

    final var window = new HousekeepingWindow(LocalTime.of(23, 0), LocalTime.of(1, 0), VIENNA);

    assertTrue(window.isOpenAt(viennaAt("2026-09-25", "23:30")));
    assertTrue(window.isOpenAt(viennaAt("2026-09-26", "00:30")));
    assertFalse(window.isOpenAt(viennaAt("2026-09-26", "01:00")));
    assertFalse(window.isOpenAt(viennaAt("2026-09-26", "22:59")));

  }

  @Test
  @DisplayName("The window says when it closes and when it opens next")
  public void theWindowSaysWhenItTurns() {

    final var window = atNight();

    assertEquals(
        viennaAt("2026-09-25", "05:00"),
        window.closesAfter(viennaAt("2026-09-25", "04:10")),
        "a window which is open closes today");
    assertEquals(
        viennaAt("2026-09-26", "04:00"),
        window.opensAfter(viennaAt("2026-09-25", "04:10")),
        "and opens again tomorrow");
    assertEquals(
        viennaAt("2026-09-25", "04:00"),
        window.opensAfter(viennaAt("2026-09-25", "02:00")),
        "a night which has not started yet starts today");

  }

  @Test
  @DisplayName("A moment exactly on the turn moves to the next day, so no tick repeats itself")
  public void aMomentOnTheTurnMovesOn() {

    final var window = atNight();

    assertEquals(
        viennaAt("2026-09-26", "05:00"),
        window.closesAfter(viennaAt("2026-09-25", "05:00")),
        "the tick which found the window shut must not be scheduled at the same moment again");

  }

  @Test
  @DisplayName("The window keeps its local hour when the clock moves")
  public void theWindowKeepsItsLocalHourAcrossAClockChange() {

    final var window = atNight();

    // central Europe moves its clocks on the last Sunday of October, at three in the
    // morning, which is inside neither night but shortens the distance between the two
    final var beforeTheChange = viennaAt("2026-10-24", "04:30");
    final var afterTheChange = window.opensAfter(beforeTheChange);

    assertEquals(viennaAt("2026-10-25", "04:00"), afterTheChange, "four in the morning is four in the morning");
    assertTrue(window.isOpenAt(afterTheChange));

  }

  @Test
  @DisplayName("Two zones read the same moment differently, which is why the zone is configurable")
  public void theZoneDecides() {

    final var vienna = atNight();
    final var utc = new HousekeepingWindow(LocalTime.of(4, 0), LocalTime.of(5, 0), ZoneId.of("UTC"));

    final var fourInVienna = viennaAt("2026-09-25", "04:30");

    assertTrue(vienna.isOpenAt(fourInVienna));
    assertFalse(utc.isOpenAt(fourInVienna), "half past four in Vienna is half past two in UTC");

  }

}
