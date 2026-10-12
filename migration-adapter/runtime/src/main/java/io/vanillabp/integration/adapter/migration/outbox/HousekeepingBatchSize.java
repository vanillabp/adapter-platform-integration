package io.vanillabp.integration.adapter.migration.outbox;

import java.time.Duration;

/**
 * How many rows one housekeeping batch works on. It is found by measuring, not by
 * configuration.
 * <p>
 * Nobody can name that number in advance. It depends on the database, on the machine, on
 * how much history the store has, and on what else runs at that hour. A number which
 * somebody guessed once is wrong on the next installation. So the first batch of a night is
 * {@link #FIRST_BATCH} rows, and the time it took is measured. The next batch is twice as
 * large, as long as twice the time would still fit into what is left of the window.
 * <p>
 * A batch which does not fit halves the size. Without that step one wrong guess eats the
 * rest of the window. The batch runs past the deadline, and the next night starts from a
 * size which was never sound.
 * <p>
 * The next night does not start from the largest size which fitted either, but from HALF of
 * it. The database changed overnight. It grew, it was reorganised, or somebody else runs a
 * report. Half is the safety margin for a measurement which is a day old.
 * <p>
 * Every batch is measured again, not extrapolated from the first one. And the doubling stops
 * at {@link #LARGEST_BATCH}. On the gruelbox store the time does not grow with the batch at
 * all. It grows with the table, because every batch scans it. A rule which doubled because
 * of one measurement would climb forever there.
 * <p>
 * Nothing of this is persisted, and nothing needs to be. The growth is geometric, so a
 * thousand reaches a million in ten steps. A pod which restarts every day loses a few
 * seconds at the start of its window, and nothing else.
 * <p>
 * This class is not thread-safe. One housekeeping runs at a time, per store, and through the
 * lease per cluster.
 * <p>
 * It is public because the test which holds the rule is in a test package of its own, like
 * the tests of the other parts of the dispatch.
 */
public class HousekeepingBatchSize {

  /**
   * The first batch of the first night. A run also falls back to this size while no batch
   * has fitted yet.
   */
  public static final int FIRST_BATCH = 1_000;

  /**
   * The largest batch which the doubling reaches. A million rows is more than a night of
   * housekeeping ever owes. The limit keeps a store whose time does not grow with the batch
   * from climbing without end.
   */
  public static final int LARGEST_BATCH = 1_000_000;

  /**
   * A rule which has not measured anything yet, so its first batch is {@link #FIRST_BATCH}.
   */
  public HousekeepingBatchSize() {

  }

  private int size = FIRST_BATCH;

  /**
   * The largest batch which ran inside the window, zero while none has. The next window
   * starts from half of it.
   */
  private int largestThatFitted;

  /**
   * How many rows the next batch works on.
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
   * A batch ran past the end of the window. The next one is half as large. So one wrong
   * step costs one batch, not the rest of the night.
   */
  public void aBatchOverran() {

    size = Math.max(1, size / 2);

  }

  /**
   * The window closed. The next one starts from half of the largest batch which fitted in
   * this one, or from {@link #FIRST_BATCH} where none did.
   */
  public void theWindowClosed() {

    size = largestThatFitted == 0
        ? FIRST_BATCH
        : Math.max(1, largestThatFitted / 2);
    largestThatFitted = 0;

  }

}
