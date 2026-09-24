package io.vanillabp.integration.adapter.migration.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
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
 * to see. Each lane takes ONE entry beyond the one it dispatches
 * ({@link #ENTRIES_WAITING_PER_LANE}), so the poller waits for a lane instead of claiming a
 * backlog nobody is working on yet. Waiting is safe here and it is what keeps the order: the
 * poller is the only thread handing work in.
 * <p>
 * Why the aggregate decides and not who is free is decision 75 in the repository's
 * DECISIONS.md, and what a claim held too early costs is decision 79.
 */
@Slf4j
public class DispatchLanes {

  /**
   * How many entries wait at a lane while it dispatches one. Exactly one: a lane takes it the
   * moment it is free, so the lane is never idle, and a node holds two claims per lane instead
   * of a queue full of them.
   * <p>
   * What a claim costs is why the number is so small. A claimed entry renews its lease until
   * its dispatch is over, one write per entry and tick, and an entry which is only waiting for
   * its lane is renewed like the one being dispatched. A deep queue therefore pays at every
   * tick for entries nobody is working on, and where the renewals fall behind, the leases run
   * out and the operations are carried out twice - see decision 79 in the repository's
   * DECISIONS.md. A queue of one also leaves the backlog in the database, where another node
   * can take it and where an operator can read it.
   */
  static final int ENTRIES_WAITING_PER_LANE = 1;

  /**
   * How long a shutdown waits for the dispatches which are running. What is still on its
   * way out gets its moment, and an entry whose lane does not end in time stays OPEN in the
   * table, so the next poll takes it.
   */
  static final long LONGEST_WAIT_ON_SHUTDOWN_MILLIS = 5000;

  private final List<ThreadPoolExecutor> lanes;

  /**
   * Builds the lanes of one outbox dispatcher, one thread and one queue each. A lane
   * starts its thread when it is first handed work and keeps it until {@link #stop()}, so
   * the number is what this dispatcher costs in threads and in database connections held
   * at the same time.
   *
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
   * <p>
   * A lane which is stopping, and a wait for a free place which is interrupted, both mean
   * that this work will never run. The handover says so instead of returning as if the work
   * had been taken: the caller has opened things for it - the renewal of an outbox entry's
   * lease is the case - and only it can close them.
   *
   * @param threadName The name of this lane's thread
   * @return The executor of this lane
   */
  private static ThreadPoolExecutor laneExecutor(
      final String threadName) {

    return new ThreadPoolExecutor(
        1, 1, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(ENTRIES_WAITING_PER_LANE), runnable -> {
          final var thread = new Thread(runnable, threadName);
          thread.setDaemon(true);
          return thread;
        }, (
            rejected,
            rejectedBy) -> {
          if (rejectedBy.isShutdown()) {
            throw new RejectedExecutionException("the lane '%s' is stopping".formatted(threadName));
          }
          try {
            rejectedBy.getQueue().put(rejected);
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException(
                "the wait for a free place in the lane '%s' was interrupted".formatted(threadName), e);
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
   * <p>
   * The answer is what a caller which opened something for this work reads: a lane which
   * is stopping runs nothing, and the caller closes what it opened instead of leaving it to
   * whoever stops next.
   *
   * @param key What the work is ordered by, usually the workflow aggregate
   * @param work What to run
   * @return Whether a lane took the work
   */
  public boolean runInOrderOf(
      final String key,
      final Runnable work) {

    try {
      lanes
          .get(laneOf(key, lanes.size()))
          .execute(work);
      return true;
    } catch (final RejectedExecutionException e) {
      return false;
    }

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
