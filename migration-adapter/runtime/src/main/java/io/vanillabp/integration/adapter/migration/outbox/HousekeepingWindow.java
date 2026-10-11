package io.vanillabp.integration.adapter.migration.outbox;

import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;

/**
 * The hours of the day in which an outbox cleans up, read in the zone which the application
 * named.
 * <p>
 * A window is two local times and a zone. So it follows the clock of the place it was
 * meant for. It opens at four in the morning there, whether or not that country is on
 * summer time. That is why the moments are computed per day, and not by adding a fixed
 * duration. Then the two nights around a clock change come out right.
 * <p>
 * A window whose end is before its start crosses midnight. <code>23:00</code> to
 * <code>01:00</code> is two hours. The end is exclusive, so a tick exactly at the end finds
 * the window closed.
 * <p>
 * It is public because the test which holds these answers is in a test package of its own,
 * like the tests of the other parts of the dispatch.
 */
public class HousekeepingWindow {

  private final LocalTime start;

  private final LocalTime end;

  private final ZoneId zone;

  /**
   * Builds the window in which an outbox cleans up.
   *
   * @param start When the window opens, local time
   * @param end When it closes, local time
   * @param zone The zone both are read in
   */
  public HousekeepingWindow(
      final LocalTime start,
      final LocalTime end,
      final ZoneId zone) {

    this.start = start;
    this.end = end;
    this.zone = zone;

  }

  /**
   * Whether the window is open at a moment.
   *
   * @param moment The moment to ask about
   * @return Whether housekeeping may run then
   */
  public boolean isOpenAt(
      final Instant moment) {

    final var local = moment
        .atZone(zone)
        .toLocalTime();
    if (start.isBefore(end)) {
      return !local.isBefore(start) && local.isBefore(end);
    }
    // the window crosses midnight, so it is open on both sides of midnight
    return !local.isBefore(start) || local.isBefore(end);

  }

  /**
   * When the window which is open now closes. Where none is open, when the next one
   * closes.
   *
   * @param moment The moment to count from
   * @return The next moment the window is shut, always after the given one
   */
  public Instant closesAfter(
      final Instant moment) {

    return next(end, moment);

  }

  /**
   * When the window opens next.
   *
   * @param moment The moment to count from
   * @return The next moment the window opens, always after the given one
   */
  public Instant opensAfter(
      final Instant moment) {

    return next(start, moment);

  }

  /**
   * The next occurrence of a local time, strictly after a moment.
   *
   * @param time The local time to look for
   * @param moment The moment to count from
   * @return That time on the next day it comes round
   */
  private Instant next(
      final LocalTime time,
      final Instant moment) {

    final var today = moment
        .atZone(zone)
        .toLocalDate()
        .atTime(time)
        .atZone(zone)
        .toInstant();
    if (today.isAfter(moment)) {
      return today;
    }
    return moment
        .atZone(zone)
        .toLocalDate()
        .plusDays(1)
        .atTime(time)
        .atZone(zone)
        .toInstant();

  }

}
