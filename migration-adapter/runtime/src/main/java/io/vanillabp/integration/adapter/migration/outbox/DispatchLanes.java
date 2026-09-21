package io.vanillabp.integration.adapter.migration.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * The threads an outbox dispatches on, and the rule which decides which entry goes to
 * which of them: the key of the workflow aggregate. Entries of one aggregate therefore
 * leave in the order they were written, and entries of different aggregates leave at the
 * same time.
 * <p>
 * A pool handing the next free thread the next entry would do neither. Two operations of
 * one workflow would overtake each other, the BPMS would see them in the wrong order, and
 * nothing downstream would notice until a customer did. So every lane is a thread of its
 * own with a queue of its own, and the lane is picked from the key rather than from who
 * is free.
 * <p>
 * The number of lanes is bounded (<code>vanillabp.outbox.dispatch-threads</code>) because
 * an unbounded one would only move the limit into the connection pool, where it is harder
 * to see. The queues are bounded for the same reason: a poller which claimed a backlog
 * waits for a lane to take the next entry instead of holding the whole backlog in memory.
 * Waiting is safe here and it is what keeps the order: the poller is the only thread
 * handing work in.
 * <p>
 * Why the aggregate decides and not who is free is decision 75 in the repository's
 * DECISIONS.md.
 */
@Slf4j
public class DispatchLanes {

  /**
   * How many entries one lane holds before the poller has to wait for it. Enough that a
   * lane is never idle while work is waiting, small enough that a backlog stays in the
   * database, which is where it can be read.
   */
  static final int QUEUE_LENGTH_PER_LANE = 16;

  /**
   * How long a shutdown waits for the dispatches which are running. What is still on its
   * way out gets its moment, and an entry whose lane does not end in time stays OPEN in the
   * table, so the next poll takes it.
   */
  static final long LONGEST_WAIT_ON_SHUTDOWN_MILLIS = 5000;

  private final List<ThreadPoolExecutor> lanes;

  /**
   * @param threadName What the threads are called, which is what an operator reads in a
   *          thread dump; the lane's number is appended
   * @param count How many lanes to run, at least one
   * @throws IllegalArgumentException If fewer than one lane was asked for. Reading it as
   *           one would leave an application believing it had switched the dispatch off,
   *           and there is no such switch: an outbox which dispatches nothing is an outbox
   *           whose entries never reach the BPMS
   */
  public DispatchLanes(
      final String threadName,
      final int count) {

    if (count < 1) {
      throw new IllegalArgumentException(
          """
              'vanillabp.outbox.dispatch-threads' is %d! The outbox dispatches on at least one \
              thread, so set it to one or more. One thread is what every VanillaBP release before \
              2.0 did."""
              .formatted(count));
    }
    this.lanes = new ArrayList<>(count);
    for (var lane = 0; lane < count; lane++) {
      lanes.add(laneExecutor(threadName
          + "-"
          + lane));
    }

  }

  /**
   * One lane: one thread, a bounded queue, and a full queue which makes the caller wait
   * rather than run the work itself. Running it in the caller is what a
   * {@code CallerRunsPolicy} would do, and it is exactly what must not happen here - the
   * entry would then run beside the lane which holds the earlier entry of the same
   * aggregate.
   *
   * @param threadName The name of this lane's thread
   * @return The executor of this lane
   */
  private static ThreadPoolExecutor laneExecutor(
      final String threadName) {

    return new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_LENGTH_PER_LANE), runnable -> {
          final var thread = new Thread(runnable, threadName);
          thread.setDaemon(true);
          return thread;
        }, (
            rejected,
            rejectedBy) -> {
          if (rejectedBy.isShutdown()) {
            return;
          }
          try {
            rejectedBy.getQueue().put(rejected);
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

  }

  /**
   * How many lanes are running.
   *
   * @return The number of lanes
   */
  public int count() {

    return lanes.size();

  }

  /**
   * The lane a key belongs to. The same key always names the same lane, which is the
   * whole of the ordering guarantee, and a key of one aggregate never depends on what
   * else is running.
   *
   * @param key What the entry is ordered by
   * @param count How many lanes there are
   * @return The number of the lane serving that key
   */
  public static int laneOf(
      final String key,
      final int count) {

    return Math.floorMod(key.hashCode(), count);

  }

  /**
   * Runs the work on the lane of the given key, after everything handed in for that key
   * before it. Waits where the lane's queue is full.
   *
   * @param key What the work is ordered by, usually the workflow aggregate
   * @param work What to run
   */
  public void runInOrderOf(
      final String key,
      final Runnable work) {

    lanes
        .get(laneOf(key, lanes.size()))
        .execute(work);

  }

  /**
   * Stops the lanes and waits a moment for what is running to end. What is still queued
   * is dropped: every one of those entries is an outbox entry which stays OPEN, so the
   * next poll of this node or of another one picks it up again.
   */
  public void stop() {

    lanes.forEach(ThreadPoolExecutor::shutdownNow);
    // one deadline for all of them, not one per lane: a shutdown which waits five seconds
    // per lane would hold an application back for as many seconds as it has threads
    final var giveUpAt = System.currentTimeMillis() + LONGEST_WAIT_ON_SHUTDOWN_MILLIS;
    for (final var lane : lanes) {
      final var left = giveUpAt - System.currentTimeMillis();
      if (left <= 0) {
        return;
      }
      try {
        lane.awaitTermination(left, TimeUnit.MILLISECONDS);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }

  }

}
