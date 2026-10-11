package io.vanillabp.integration.adapter.migration.outbox;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import lombok.extern.slf4j.Slf4j;

/**
 * What an outbox store throws away, and when.
 * <p>
 * Two kinds of row are removed. One is the entries which were dispatched longer ago than
 * <code>vanillabp.outbox.retention</code>. The other is the payloads which no entry names
 * any more. Both used to run at the end of every poll. So every application paid for them
 * all day long, and nobody could say what one poll cost. Now they run in a window at night
 * (<code>vanillabp.outbox.housekeeping.*</code>). Inside that window the store removes as
 * much as fits.
 * <p>
 * <strong>The entries go first.</strong> They are the bulk. And the question about the
 * orphaned payloads searches the table which they leave behind.
 * <p>
 * <strong>How much fits is measured, not configured.</strong> The rule lives here and not in
 * the stores. Otherwise the same awkward arithmetic would be in four places, and every claim
 * about it would depend on four copies staying equal. {@link HousekeepingBatchSize} says
 * what it does.
 * <p>
 * <strong>One node at a time.</strong> Two nodes which clean up at the same time would each
 * measure the other's work, and their batch size would be nonsense. So one node claims the
 * store for the length of the window, in the store's own database. The other nodes leave it
 * alone that night. The claim lasts until the window closes. It is not renewed while the
 * work runs, because unlike a dispatch this work has a known end. A node which dies inside
 * the window holds the store until that end. That costs the rest of one night, and the
 * meters show it. The shared Hazelcast cache is deliberately NOT used for this. It is
 * optional. An application without it would then clean up either not at all or without
 * coordination. But every application with an outbox has the database.
 * <p>
 * <strong>The lease is per STORE.</strong> An application in a migration has a JPA outbox
 * and a MongoDB outbox. Those are two databases. Two nodes may clean up one each without
 * spoiling each other's numbers.
 * <p>
 * <strong>What a window leaves behind is published</strong> through three meters. They are
 * set when the window closes, and held until the next one closes (see
 * {@link VanillaBpMetrics#registerHousekeeping(String, Supplier, Supplier, Supplier)}).
 * <p>
 * A node which is down for the whole window does not clean up that night, and nothing
 * catches up later. That is on purpose. What such a mechanism should do would be a guess,
 * and the meters show the night which was missed.
 */
@Slf4j
public class OutboxHousekeeping {

  /**
   * What one outbox store lets its housekeeping do to it. Every store which VanillaBP ships
   * implements this on its dispatcher, where the table or the collection is known.
   */
  public interface Store {

    /**
     * The name by which this store is known, in the log and as the <code>store</code> tag of
     * the meters. It is the same value with which the store registers its other meters.
     *
     * @return The name
     */
    String storeName();

    /**
     * Claims the right to clean up this store until a moment, for one node.
     * <p>
     * It is an optimistic write. The node whose write went through holds the store. Every
     * other node gets no as the answer, and leaves the store alone.
     *
     * @param owner Which node is claiming
     * @param until When the claim runs out by itself
     * @return Whether this node holds the store now
     */
    boolean claimHousekeepingUntil(
        String owner,
        Instant until);

    /**
     * Gives the claim back. So a node which cleaned up early in a long window does not hold
     * the store for the rest of it.
     *
     * @param owner The node which claimed
     */
    void releaseHousekeeping(
        String owner);

    /**
     * Removes entries which were dispatched before a moment. This is the half of the "DONE
     * instead of delete" contract which runs later.
     *
     * @param threshold Entries dispatched before this moment
     * @param maxRows The most entries to remove, a ceiling and not a target
     * @return How many went, never more than <code>maxRows</code>
     */
    int removeDispatchedEntriesOlderThan(
        Instant threshold,
        int maxRows);

    /**
     * Removes payloads written before a moment which no entry names any more.
     *
     * @param threshold Payloads written before this moment
     * @param maxRows The most payloads to remove
     * @return How many went, never more than <code>maxRows</code>
     */
    int removeOrphanedPayloadsOlderThan(
        Instant threshold,
        int maxRows);

    /**
     * How many dispatched entries are still older than the retention. That is what the window
     * did not get to.
     * <p>
     * It is a <code>COUNT</code> and so a real question. That is why it is asked once per
     * window and not once per poll. It reads the index which the retention delete already
     * uses. The orphaned payloads are not counted with it. On the stores which cannot index the
     * reference, that count is the expensive scan which used to run in every poll.
     *
     * @param threshold Entries dispatched before this moment
     * @return How many there are, empty where the store could not say
     */
    OptionalLong countDispatchedEntriesOlderThan(
        Instant threshold);

  }

  /**
   * How long the claim is held beyond the end of the window. It covers the batch which still
   * runs when the window closes. So the claim does not go to another node while this one is
   * still writing.
   */
  private static final Duration BEYOND_THE_WINDOW = Duration.ofMinutes(5);

  /**
   * The longest a shutdown waits for the claim to be given back. Two seconds is plenty for
   * one write. And it is short enough that a database which is already gone does not hold up
   * the shutdown for the timeout of its own client.
   */
  private static final Duration GIVING_THE_CLAIM_BACK = Duration.ofSeconds(2);

  private final Store store;

  private final PhaseTwoOutboxProperties properties;

  private final Supplier<VanillaBpMetrics> metrics;

  private final HousekeepingWindow window;

  /**
   * Which node holds a claim, as the row shows it in a support case. An operator recognises
   * the host and the process. The random tail tells two runs of the same process apart. It
   * is built exactly like in {@link DispatchLease}.
   */
  private final String owner;

  private final HousekeepingBatchSize entryBatches = new HousekeepingBatchSize();

  private final HousekeepingBatchSize payloadBatches = new HousekeepingBatchSize();

  private ScheduledExecutorService ticks;

  /**
   * Whether the last tick found the window open. That is the only way to notice that a
   * window closed.
   */
  private volatile boolean windowIsOpen;

  /**
   * Whether this node won the claim on the open window. Then it does the work and publishes
   * the numbers.
   */
  private volatile boolean holdsTheStore;

  private Instant openedAt;

  private long removedInThisWindow;

  private volatile OptionalLong remainingAfterTheWindow = OptionalLong.empty();

  private volatile OptionalLong removedInTheLastWindow = OptionalLong.empty();

  private volatile Optional<Duration> windowUsed = Optional.empty();

  /**
   * Builds the housekeeping of one store.
   *
   * @param store The store to house-keep
   * @param properties The bound <code>vanillabp.outbox</code> section, read for the window,
   *          the zone and the retention
   * @param metrics Where the three numbers of a closed window are published
   */
  public OutboxHousekeeping(
      final Store store,
      final PhaseTwoOutboxProperties properties,
      final Supplier<VanillaBpMetrics> metrics) {

    this.store = store;
    this.properties = properties;
    this.metrics = metrics;
    final var housekeeping = properties.getHousekeeping();
    this.window = new HousekeepingWindow(
        housekeeping.getStart(), housekeeping.getEnd(), housekeeping.resolvedZone());
    this.owner = "%s/%s/%s"
        .formatted(
            hostName(),
            ManagementFactory
                .getRuntimeMXBean()
                .getName(),
            UUID
                .randomUUID()
                .toString()
                .substring(0, 8));

  }

  private static String hostName() {

    try {
      return java.net.InetAddress
          .getLocalHost()
          .getHostName();
    } catch (final Exception e) {
      return "unknown-host";
    }

  }

  /**
   * Publishes the three meters of this store and starts looking for its window. The
   * dispatcher calls it once the application is ready. So nothing is cleaned up while the
   * models are still on their way to the BPMS.
   */
  public void start() {

    final var publishTo = metrics.get();
    if (publishTo != null) {
      publishTo
          .registerHousekeeping(
              store.storeName(),
              () -> remainingAfterTheWindow,
              () -> removedInTheLastWindow,
              () -> windowUsed);
    }
    ticks = Executors
        .newSingleThreadScheduledExecutor(runnable -> {
          final var thread = new Thread(runnable, "vanillabp-outbox-housekeeping");
          thread.setDaemon(true);
          return thread;
        });
    scheduleAt(Instant.now());

  }

  /**
   * Stops looking for the window, and gives a claim back where this node holds one. So a
   * node which takes over does not wait for the claim of a process which is gone.
   * <p>
   * The claim is given back on the thread which took it. This call waits
   * {@link #GIVING_THE_CLAIM_BACK} for that, and no longer. A shutdown often takes the
   * database with it: the connection pool is closed, or the container of a test is already
   * stopped. A client which then waits for a server it will not find would hold up the whole
   * shutdown for as long as its own timeout. Giving up loses nothing, because the claim runs
   * out by itself when the window ends.
   */
  public void stop() {

    final var running = ticks;
    ticks = null;
    if (running == null) {
      return;
    }
    if (holdsTheStore) {
      holdsTheStore = false;
      running.execute(() -> store.releaseHousekeeping(owner));
    }
    running.shutdown();
    try {
      if (!running.awaitTermination(GIVING_THE_CLAIM_BACK.toMillis(), TimeUnit.MILLISECONDS)) {
        running.shutdownNow();
      }
    } catch (final InterruptedException e) {
      Thread
          .currentThread()
          .interrupt();
      running.shutdownNow();
    }

  }

  /**
   * One look at the clock. Outside the window it only works out when to look again. Inside
   * the window it removes what fits, until the window closes or nothing is left.
   * <p>
   * Inside the window it comes back every <code>vanillabp.outbox.poll-interval</code>, and
   * does not run only once. An application writes entries while the window is open. A store
   * which was empty at four o'clock may have work at half past.
   */
  private void tick() {

    Instant nextLook;
    try {
      final var now = Instant.now();
      if (window.isOpenAt(now)) {
        nextLook = workInsideTheWindow(now);
      } else {
        nextLook = window.opensAfter(now);
        if (windowIsOpen) {
          closeTheWindow(now);
        }
      }
    } catch (final Exception e) {
      log.error("The housekeeping of the VanillaBP outbox store '{}' failed - will retry", store.storeName(), e);
      nextLook = Instant
          .now()
          .plus(properties.getPollInterval());
    }
    scheduleAt(nextLook);

  }

  /**
   * One pass inside an open window.
   *
   * @param now The moment this tick started
   * @return When to look again
   */
  private Instant workInsideTheWindow(
      final Instant now) {

    final var closesAt = window.closesAfter(now);
    if (!windowIsOpen) {
      openTheWindow(now, closesAt);
    }
    if (holdsTheStore) {
      removedInThisWindow += removeWhatFits(closesAt);
    }
    final var nextLook = now.plus(properties.getPollInterval());
    return nextLook.isBefore(closesAt) ? nextLook : closesAt;

  }

  /**
   * Claims the store for this window and starts counting what the window removes.
   *
   * @param now The moment the window was found open
   * @param closesAt When it closes
   */
  private void openTheWindow(
      final Instant now,
      final Instant closesAt) {

    windowIsOpen = true;
    openedAt = now;
    removedInThisWindow = 0;
    holdsTheStore = store.claimHousekeepingUntil(owner, closesAt.plus(BEYOND_THE_WINDOW));
    if (holdsTheStore) {
      log.debug("House-keeping the VanillaBP outbox store '{}' until {}", store.storeName(), closesAt);
    } else {
      log
          .debug(
              "Another node is house-keeping the VanillaBP outbox store '{}' tonight",
              store.storeName());
    }

  }

  /**
   * Publishes what the window did, gives the claim back, and forgets the batch sizes of this
   * night. The next night starts from half of what fitted, as
   * {@link HousekeepingBatchSize#theWindowClosed()} works out.
   *
   * @param now The moment the window was found shut
   */
  private void closeTheWindow(
      final Instant now) {

    windowIsOpen = false;
    entryBatches.theWindowClosed();
    payloadBatches.theWindowClosed();
    if (!holdsTheStore) {
      return;
    }
    holdsTheStore = false;
    remainingAfterTheWindow = store.countDispatchedEntriesOlderThan(retentionRunsOutBefore());
    removedInTheLastWindow = OptionalLong.of(removedInThisWindow);
    windowUsed = Optional.of(Duration.between(openedAt, now));
    store.releaseHousekeeping(owner);
    reportTheWindow();

  }

  /**
   * Says what the window did, once a night. Where something was left over, it says so at
   * INFO. Somebody who decides whether to widen the window reads that line. A window which
   * got through says the same at DEBUG, so a healthy log stays quiet.
   */
  private void reportTheWindow() {

    final var leftOver = remainingAfterTheWindow.orElse(0);
    if (leftOver > 0) {
      log
          .info(
              "The housekeeping window of the VanillaBP outbox store '{}' removed {} row(s) in {} and "
                  + "left {} dispatched entrie(s) behind - widen 'vanillabp.outbox.housekeeping.start' to "
                  + "'.end' if this repeats",
              store.storeName(),
              removedInThisWindow,
              windowUsed.orElse(Duration.ZERO),
              leftOver);
      return;
    }
    log
        .debug(
            "The housekeeping window of the VanillaBP outbox store '{}' removed {} row(s) in {}",
            store.storeName(),
            removedInThisWindow,
            windowUsed.orElse(Duration.ZERO));

  }

  /**
   * Removes both kinds of row, entries first, until the window closes or nothing is left.
   *
   * @param closesAt When the window closes
   * @return How many rows went
   */
  private long removeWhatFits(
      final Instant closesAt) {

    final var threshold = retentionRunsOutBefore();
    // the dispatched entries first. They are the bulk, and the question about the orphaned
    // payloads searches the table which they leave behind
    return sweep(entryBatches, closesAt, rows -> store.removeDispatchedEntriesOlderThan(threshold, rows)) + sweep(
        payloadBatches, closesAt, rows -> store.removeOrphanedPayloadsOlderThan(threshold, rows));

  }

  private Instant retentionRunsOutBefore() {

    return Instant
        .now()
        .minus(properties.getRetention());

  }

  /**
   * What one kind of row costs, measured batch by batch.
   *
   * @param batches The size rule of this kind of row
   * @param closesAt When the window closes
   * @param removeAtMost Removes that many rows and says how many it was
   * @return How many rows went
   */
  private long sweep(
      final HousekeepingBatchSize batches,
      final Instant closesAt,
      final OneBatch removeAtMost) {

    var removed = 0L;
    while (Instant
        .now()
        .isBefore(closesAt)) {
      final var size = batches.size();
      final var startedAt = System.nanoTime();
      final var went = removeAtMost.remove(size);
      final var took = Duration.ofNanos(System.nanoTime() - startedAt);
      removed += went;
      final var now = Instant.now();
      if (now.isAfter(closesAt)) {
        // one wrong step must not cost the rest of the night. So the next batch is half the
        // size, here and, through the rule, tomorrow as well
        batches.aBatchOverran();
        break;
      }
      batches.aBatchFitted(took, Duration.between(now, closesAt));
      if (went < size) {
        // a batch which came back short is the last one. There was nothing more of this kind
        // to remove
        break;
      }
    }
    return removed;

  }

  /**
   * One batch of one kind of row, so that the two sweeps read the same way.
   */
  @FunctionalInterface
  private interface OneBatch {

    /**
     * Removes at most so many rows.
     *
     * @param maxRows The ceiling
     * @return How many went
     */
    int remove(
        int maxRows);

  }

  private void scheduleAt(
      final Instant moment) {

    final var running = ticks;
    if (running == null) {
      return;
    }
    final var delay = Math.max(0, Duration.between(Instant.now(), moment).toMillis());
    try {
      running.schedule(this::tick, delay, TimeUnit.MILLISECONDS);
    } catch (final java.util.concurrent.RejectedExecutionException e) {
      // the node is stopping, which is not a failure of the housekeeping
      log.debug("The housekeeping of the VanillaBP outbox store '{}' stopped", store.storeName());
    }

  }

}
