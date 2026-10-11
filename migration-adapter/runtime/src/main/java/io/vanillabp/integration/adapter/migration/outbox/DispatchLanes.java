package io.vanillabp.integration.adapter.migration.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import lombok.extern.slf4j.Slf4j;

/**
 * The threads on which an outbox dispatches, and the rule which decides which entry goes to
 * which thread: the key of the workflow aggregate. So entries of one aggregate leave in the
 * order they were written, and entries of different aggregates leave at the same time.
 * <p>
 * A pool which gives the next entry to the next free thread would do neither. Two
 * operations of one workflow would overtake each other. The BPMS would see them in the
 * wrong order, and nothing downstream would notice until a customer did. So every lane is a
 * thread of its own with a queue of its own. The lane is picked from the key, not from who
 * is free.
 * <p>
 * The number of lanes is limited (<code>vanillabp.outbox.dispatch-threads</code>). Without
 * a limit, the limit would only move into the connection pool, where it is harder to see.
 * Each lane takes ONE entry beyond the one it dispatches
 * ({@link #ENTRIES_WAITING_PER_LANE}). So the poller waits for a lane, instead of claiming a
 * backlog which nobody works on yet. Waiting is safe here, and it keeps the order, because
 * the poller is the only thread which hands in work.
 * <p>
 * Decision 75 in the repository's DECISIONS.md says why the aggregate decides and not who is
 * free. Decision 79 says what a claim held too early costs.
 */
@Slf4j
public class DispatchLanes {

  /**
   * How many entries wait at a lane while it dispatches one. Exactly one. A lane takes it at
   * the moment it is free, so the lane is never idle. And a node holds two claims per lane,
   * not a queue full of them.
   * <p>
   * The number is so small because of what a claim costs. A claimed entry renews its lease
   * until its dispatch is over, with one write per entry and tick. An entry which only waits
   * for its lane is renewed like the one which is dispatched. So a deep queue pays at every
   * tick for entries which nobody works on. Where the renewals fall behind, the leases run
   * out, and the operations are carried out twice (see decision 79 in the repository's
   * DECISIONS.md). A queue of one also leaves the backlog in the database. There another node
   * can take it, and an operator can read it.
   */
  static final int ENTRIES_WAITING_PER_LANE = 1;

  /**
   * How long a shutdown waits for the running dispatches. What is still on its way out gets
   * its moment. An entry whose lane does not end in time stays OPEN in the table, so the
   * next poll takes it.
   */
  static final long LONGEST_WAIT_ON_SHUTDOWN_MILLIS = 5000;

  private final List<ThreadPoolExecutor> lanes;

  /**
   * Builds the lanes of one outbox dispatcher, one thread and one queue each. A lane starts
   * its thread when it first gets work, and keeps it until {@link #stop()}. So the number of
   * lanes is what this dispatcher costs in threads, and in database connections held at the
   * same time.
   *
   * @param threadName What the threads are called. An operator reads it in a thread dump.
   *          The lane's number is added
   * @param count How many lanes to run, at least one
   * @throws IllegalArgumentException If fewer than one lane was asked for. Reading that as
   *           one would make an application believe it had switched the dispatch off. There
   *           is no such switch. An outbox which dispatches nothing is an outbox whose
   *           entries never reach the BPMS
   */
  public DispatchLanes(
      final String threadName,
      final int count) {

    if (count < 1) {
      throw new IllegalArgumentException(
          """
              '%s' is %d! The outbox dispatches on at least one \
              thread, so set it to one or more. One thread is what every VanillaBP release before \
              2.0 did."""
              .formatted(PhaseTwoOutboxProperties.DISPATCH_THREADS_PROPERTY, count));
    }
    this.lanes = new ArrayList<>(count);
    for (var lane = 0; lane < count; lane++) {
      lanes.add(laneExecutor(threadName
          + "-"
          + lane));
    }

  }

  /**
   * One lane: one thread, a limited queue, and a full queue which makes the caller wait
   * instead of running the work itself. Running it in the caller is what a
   * {@code CallerRunsPolicy} would do, and that must not happen here. The entry would then
   * run next to the lane which holds the earlier entry of the same aggregate.
   * <p>
   * A lane which is stopping, and a wait for a free place which is interrupted, both mean
   * that this work will never run. The handover says so, instead of returning as if the work
   * had been taken. The caller has opened things for the work, for example the renewal of the
   * lease of an outbox entry. Only the caller can close them.
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
   * The lane to which a key belongs. The same key always names the same lane. That is the
   * whole of the ordering guarantee. The lane of an aggregate's key never depends on what
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
   * before. It waits where the lane's queue is full.
   * <p>
   * A caller which opened something for this work reads the answer. A lane which is stopping
   * runs nothing. Then the caller closes what it opened, and does not leave it to whoever
   * stops next.
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
   * Stops the lanes, and waits a moment for what is running to end. What is still queued is
   * dropped. Each of those is an outbox entry which stays OPEN. So the next poll of this
   * node or of another one picks it up again.
   */
  public void stop() {

    lanes.forEach(ThreadPoolExecutor::shutdownNow);
    // one deadline for all lanes, not one per lane. A shutdown which waited five seconds
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
