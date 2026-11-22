package io.vanillabp.loadharness;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Counts the database statements which one piece of work runs on the current thread. A
 * count does not move with the speed of the machine, so it is the number the run can fail
 * on. A time cannot do that: on a shared runner it spreads by half between two runs of the
 * same work.
 * <p>
 * The statements are seen by {@link CountingDriver}, which sits in front of the real JDBC
 * driver. A statement counts for the thread which runs it. So a count of a workflow start
 * holds what the start ran in the caller's transaction, and nothing which the outbox did on
 * its own thread at the same moment.
 */
public final class StatementCounter {

  private static final ThreadLocal<long[]> CURRENT = new ThreadLocal<>();

  private static final AtomicLong TOTAL = new AtomicLong();

  private StatementCounter() {
  }

  /**
   * What a piece of work returned, and how many statements it ran.
   *
   * @param <T> What the work returned
   * @param result What the work returned
   * @param statements How many statements it ran on this thread
   */
  public record Counted<T>(
                           T result,
                           long statements) {
  }

  /**
   * Runs the work and counts the statements it runs on this thread.
   *
   * @param <T> What the work returns
   * @param work The work
   * @return What it returned, with the count
   */
  public static <T> Counted<T> count(
      final Supplier<T> work) {

    final var outer = CURRENT.get();
    final var counter = new long[1];
    CURRENT.set(counter);
    try {
      final var result = work.get();
      return new Counted<>(result, counter[0]);
    } finally {
      if (outer == null) {
        CURRENT.remove();
      } else {
        // a count inside a count belongs to both
        outer[0] += counter[0];
        CURRENT.set(outer);
      }
    }

  }

  /**
   * All statements which ran through {@link CountingDriver} since the JVM started, on any
   * thread.
   *
   * @return The number of statements
   */
  public static long total() {

    return TOTAL.get();

  }

  static void ran() {

    TOTAL.incrementAndGet();
    final var counter = CURRENT.get();
    if (counter != null) {
      counter[0]++;
    }

  }

}
