package io.vanillabp.integration.adapter.migration.outbox;

import java.time.Duration;

/**
 * How many rows one housekeeping batch takes on, found by measuring instead of by
 * configuration.
 * <p>
 * Nobody can name that number in advance. It depends on the database, on the machine, on
 * how much history the store carries and on what else runs at that hour, and a number
 * somebody guessed once is wrong on the next installation. So the first batch of a night
 * is {@link #FIRST_BATCH} rows, the time it took is measured, and the next batch is twice
 * as large while twice the time would still fit in what is left of the window.
 * <p>
 * A batch which does not fit halves the size. Without that step one wrong guess eats the
 * rest of the window: the batch runs past the deadline, and the next night starts from a
 * size which was never sound.
 * <p>
 * The next night does not start from the largest size which fitted either, but from HALF
 * of it. The database changed over night - it grew, it was reorganised, somebody else is
 * running a report - and half is the distance kept from a measurement which is a day old.
 * <p>
 * Every batch is measured again rather than extrapolated from the first one, and the ramp
 * upwards is capped at {@link #LARGEST_BATCH}. On the gruelbox store the time does not
 * grow with the batch at all, it grows with the table, because every batch scans it; a
 * rule which doubled on the strength of one measurement would climb forever there.
 * <p>
 * Nothing of this is persisted, and it is not owed: the ramp is geometric, so a thousand
 * reaches a million in ten steps. A pod which restarts every day loses a few seconds at
 * the beginning of its window and nothing else.
 * <p>
 * This class is not thread-safe. One housekeeping runs at a time, per store and, through
 * the lease, per cluster.
 * <p>
 * It is public because the test which holds the rule lives in a test package of its own,
 * the way the other parts of the dispatch do.
 */
public class HousekeepingBatchSize {

  /**
   * The first batch of the first night, and the size a run falls back to while no batch
   * has fitted yet.
   */
  public static final int FIRST_BATCH = 1_000;

  /**
   * The largest batch the doubling reaches. A million rows is more than a night of
   * housekeeping ever owes, and the cap is what keeps a store whose time does not grow
   * with the batch from climbing without end.
   */
  public static final int LARGEST_BATCH = 1_000_000;

  /**
   * A rule which has measured nothing yet, so its first batch is {@link #FIRST_BATCH}.
   */
  public HousekeepingBatchSize() {

  }

  private int size = FIRST_BATCH;

  /**
   * The largest batch which ran inside the window, zero while none has. It is what the
   * next window starts from, halved.
   */
  private int largestThatFitted;

  /**
   * How many rows the next batch takes on.
   *
   * @return The size of the next batch, never below one
   */
  public int size() {

    return size;

  }

  /**
   * A batch ran and the window was still open when it ended.
   *
   * @param took How long the batch took
   * @param leftOfTheWindow How much of the window is left now
   */
  public void aBatchFitted(
      final Duration took,
      final Duration leftOfTheWindow) {

    largestThatFitted = Math.max(largestThatFitted, size);
    if (took
        .multipliedBy(2)
        .compareTo(leftOfTheWindow) <= 0) {
      size = Math.min(LARGEST_BATCH, size * 2);
    }

  }

  /**
   * A batch ran past the end of the window. The next one is half as large, so that one
   * wrong step costs one batch rather than the rest of the night.
   */
  public void aBatchOverran() {

    size = Math.max(1, size / 2);

  }

  /**
   * The window closed. What the next one starts from is half of the largest batch which
   * fitted in this one, and {@link #FIRST_BATCH} where none did.
   */
  public void theWindowClosed() {

    size = largestThatFitted == 0
        ? FIRST_BATCH
        : Math.max(1, largestThatFitted / 2);
    largestThatFitted = 0;

  }

}
