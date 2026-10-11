package io.vanillabp.integration.adapter.migration.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import lombok.extern.slf4j.Slf4j;

/**
 * The clock of an outbox dispatcher. It runs the dispatcher's poll. Then it asks the store
 * when its earliest unfinished entry is due, and sleeps until exactly that moment. A store
 * which owes nothing is left alone until the maximum below has passed. So an application
 * which waits in a timer sends no database command at all.
 * <p>
 * The four stores which VanillaBP ships on both platforms share it. They differ in HOW they
 * read the next due time, not in what they do with it. Each one keeps its own private
 * single-thread daemon executor. No framework scheduler is registered or used, so an
 * application's own scheduling setup stays as it is.
 * <p>
 * A commit which planned an operation calls {@link #somethingIsDueAt(Instant)}. That is the
 * fast path, not an optimization. It moves the sleep forward to the named moment. It leaves
 * a sleep alone which already ends earlier. So an entry due in three seconds shortens an hour
 * of sleep, and an entry due in an hour shortens nothing.
 * <p>
 * Decision 42 in the repository's DECISIONS.md says why nothing wakes the OTHER nodes of a
 * cluster, and what the maximum is for.
 */
@Slf4j
public class DueEntryPoller {

  /**
   * The shortest time between two polls. It guards against a spin. A store which answers "due
   * now" to every question would otherwise be asked as fast as the thread can ask. Two nodes
   * which race for the same entry come close to that case.
   */
  private static final Duration SHORTEST_SLEEP = Duration.ofMillis(50);

  private final String threadName;

  private final Runnable poll;

  /**
   * When the store's earliest unfinished entry is due, or <code>null</code> where the store
   * owes nothing.
   */
  private final Supplier<Instant> earliestDueAt;

  private final Duration longestSleep;

  private ScheduledExecutorService executor;

  private ScheduledFuture<?> nextPoll;

  /**
   * The moment at which {@link #nextPoll} is going to run. A notification reads it to know
   * whether it has anything to move forward.
   */
  private Instant nextPollAt;

  /**
   * Builds the poller of one store. Nothing runs, and no thread exists, until
   * {@link #start()} is called. So a store may build its poller while the application is
   * still coming up.
   *
   * @param threadName The name of the daemon thread which polls. An operator reads it in a
   *          thread dump
   * @param longestSleep How long the poller sleeps while the store owes nothing
   * @param poll Dispatches what is due in one store, exceptions included
   * @param earliestDueAt Reads when the store's earliest unfinished entry is due
   */
  public DueEntryPoller(
      final String threadName,
      final Duration longestSleep,
      final Runnable poll,
      final Supplier<Instant> earliestDueAt) {

    this.threadName = threadName;
    this.longestSleep = longestSleep;
    this.poll = poll;
    this.earliestDueAt = earliestDueAt;

  }

  /**
   * Starts polling. The first poll runs at once. It dispatches the entries which an
   * instance that crashed earlier left behind. A second call does nothing.
   */
  public synchronized void start() {

    if (executor != null) {
      return;
    }
    executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final var thread = new Thread(runnable, threadName);
      thread.setDaemon(true);
      return thread;
    });
    pollAt(Instant.now());

  }

  /**
   * Stops polling on shutdown of the application. A second call does nothing.
   */
  public synchronized void stop() {

    if (executor == null) {
      return;
    }
    executor.shutdownNow();
    executor = null;
    nextPoll = null;
    nextPollAt = null;

  }

  /**
   * Moves the next poll forward to the given moment, or leaves it where it is when it already
   * ends earlier. It is called after a commit which planned an operation, with the moment the
   * operation is due.
   *
   * @param dueAt When the planned entry wants to be dispatched
   */
  public synchronized void somethingIsDueAt(
      final Instant dueAt) {

    pollAt(dueAt);

  }

  private synchronized void pollAt(
      final Instant moment) {

    if (executor == null) {
      return;
    }
    if ((nextPoll != null) && !nextPoll.isDone()) {
      if ((nextPollAt != null) && !moment.isBefore(nextPollAt)) {
        return;
      }
      nextPoll.cancel(false);
    }
    nextPollAt = moment;
    final var sleep = Math.max(0L, Duration.between(Instant.now(), moment).toMillis());
    nextPoll = executor.schedule(this::pollAndSleepUntilTheNextEntryIsDue, sleep, TimeUnit.MILLISECONDS);

  }

  private void pollAndSleepUntilTheNextEntryIsDue() {

    // the poll which is about to run can no longer be moved forward, and this says so. Then
    // a notification which arrives DURING this poll schedules the next one. Its entry may have
    // been written after this poll read its due rows. So it needs a turn of its own, and not
    // the long sleep computed below
    forgetTheScheduledPoll();
    try {
      poll.run();
    } catch (final RuntimeException | Error e) {
      // a scheduled task which lets anything escape is never run again, and the executor
      // keeps the reason to itself. The entries of this store would stop being dispatched,
      // and nothing would say so. Each store catches what its own poll throws. So reaching
      // this means that the catching has a hole
      log.error("Polling the VanillaBP phase-two outbox threw where nothing should - will poll again", e);
    } finally {
      scheduleTheNextPoll();
    }

  }

  /**
   * Schedules the next poll, whatever happened in this one. An exception on the way to the
   * next wake-up would end the only thread which could ever dispatch the entries of this
   * store. And the executor would keep the reason to itself.
   */
  private void scheduleTheNextPoll() {

    try {
      pollAt(whenTheNextEntryIsDue());
    } catch (final RuntimeException | Error e) {
      log.error("Could not schedule the next poll of the VanillaBP phase-two outbox - retrying at the cap", e);
      try {
        pollAt(Instant.now().plus(longestSleep));
      } catch (final RuntimeException | Error ignored) {
        log.error("The VanillaBP phase-two outbox is not polled any more", ignored);
      }
    }

  }

  private synchronized void forgetTheScheduledPoll() {

    nextPoll = null;
    nextPollAt = null;

  }

  /**
   * When to look again: the due time of the store's earliest unfinished entry. It has a lower
   * limit, so a store which answers "due now" forever cannot turn into a spin. It has an
   * upper limit, the maximum which an operator configured.
   *
   * @return The moment to poll at
   */
  private Instant whenTheNextEntryIsDue() {

    final var now = Instant.now();
    final var cap = now.plus(longestSleep);
    final Instant earliest;
    try {
      earliest = earliestDueAt.get();
    } catch (final RuntimeException e) {
      // a store which cannot answer has a problem which the poll itself will report as well.
      // So this falls back to the maximum, instead of ending the only thread which could
      // recover from it
      log.warn("Could not read when the next phase-two outbox entry is due - looking again in {}", longestSleep, e);
      return cap;
    }
    if ((earliest == null) || earliest.isAfter(cap)) {
      return cap;
    }
    final var floor = now.plus(SHORTEST_SLEEP);
    return earliest.isBefore(floor) ? floor : earliest;

  }

}
