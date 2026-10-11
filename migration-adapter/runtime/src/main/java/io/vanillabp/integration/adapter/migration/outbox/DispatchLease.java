package io.vanillabp.integration.adapter.migration.outbox;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import lombok.extern.slf4j.Slf4j;

/**
 * The claim of one node on one outbox entry: who holds it, and until when. A poll writes
 * both onto the entry. No other poll, here or on another node, takes an entry whose lease
 * has not run out.
 * <p>
 * The lease is renewed from the claim until the dispatch is over. That is the whole reason
 * for this class. A dispatch calls a BPMS over the network, and it may take longer than any
 * time chosen in advance. Before the renewal existed, such a dispatch simply lost its entry
 * to the next poll, and the operation was carried out twice. Each renewal is a short
 * transaction of its own: one <code>UPDATE</code> per tick, on a connection which is
 * borrowed and given back. So nothing holds a connection for the length of a dispatch.
 * <p>
 * The renewal covers the wait before the dispatch as well. A claimed entry may wait for the
 * thread which dispatches its aggregate. A wait longer than the lease would let another node
 * take an entry which this one is about to carry out.
 * <p>
 * The tick is a third of the lease. That leaves two more renewals before the lease would
 * run out. The tick never goes below {@link #SHORTEST_TICK}. So an application which sets
 * <code>vanillabp.outbox.attempt-frequency</code> to a very small value gets a short lease,
 * and not a loop of updates. One daemon thread carries the ticks of all entries which a
 * node holds at the same time. That is enough because a node holds few of them: what its
 * lanes dispatch, plus one entry waiting at each lane
 * ({@link DispatchLanes#ENTRIES_WAITING_PER_LANE}). A renewal is one write by primary key.
 * So one thread writes as many of them per tick as the node has lanes. A database which is
 * too slow for that does not get the dispatch itself through either. Decision 79 in the
 * repository's DECISIONS.md says how this was measured, and why more renewal threads are not
 * the answer.
 * <p>
 * A renewal which matches no row means that the lease was lost. The entry was taken over, or
 * somebody else finished it. The ticking stops there and says so. From that moment on two
 * nodes may carry out the same operation, and an operator has to be able to read that
 * afterwards. The dispatch which lost the entry runs to its end, and its result is dropped.
 * Every store names the holder of the lease in the writes which say how an attempt ended.
 * So the entry keeps what the node which holds it wrote.
 * <p>
 * The lease does NOT count. The number of attempts is written when an attempt ENDED. So an
 * entry whose dispatch is slow uses up no attempts (see
 * <code>vanillabp.outbox.block-after-attempts</code>).
 */
@Slf4j
public class DispatchLease {

  /**
   * The shortest time between two renewals of one entry. It guards against a lease which is
   * so short that renewing it would be a loop of writes and not a heartbeat.
   */
  static final Duration SHORTEST_TICK = Duration.ofMillis(50);

  /**
   * Which node holds a claim, as the row shows it in a support case. An operator recognises
   * the host and the process. The random tail tells two runs of the same process apart. A
   * node which died and came back must not renew the lease it held before.
   */
  private final String owner;

  private final Duration lease;

  private final Duration tick;

  private final ScheduledExecutorService ticks;

  /**
   * What one tick does: it writes the new end of the lease onto the entry, in a transaction
   * of its own.
   */
  @FunctionalInterface
  public interface Renewal {

    /**
     * Writes the new end of the lease onto one entry, as its own short transaction. The
     * dispatcher supplies it, because only the dispatcher knows the table or the collection
     * of the entry.
     *
     * @param entryId The entry whose lease is being renewed
     * @param leaseEnd The moment the lease is to run until
     * @return Whether this node still holds the lease. It is <code>false</code> where the
     *         write matched no row. Then the entry is gone, or somebody else holds it
     */
    boolean renewUntil(
        String entryId,
        Instant leaseEnd);

  }

  /**
   * A lease which is being renewed. Closing it ends the ticking. Every dispatch closes it,
   * whether it succeeded or threw.
   */
  public interface Held extends AutoCloseable {

    @Override
    void close();

  }

  /**
   * Builds the renewal of one dispatcher: the name which this node writes into the entries
   * it claims, and the one daemon thread which carries the ticks of all entries the
   * dispatcher holds at the same time.
   *
   * @param threadName The name of the daemon thread which renews. An operator reads it in a
   *          thread dump
   * @param lease How long a claim lasts: <code>vanillabp.outbox.attempt-frequency</code>.
   *          It is also how long a failed dispatch waits before it is read again
   */
  public DispatchLease(
      final String threadName,
      final Duration lease) {

    this.owner = ownerOfThisNode();
    this.lease = lease;
    this.tick = tickOf(lease);
    this.ticks = Executors.newSingleThreadScheduledExecutor(runnable -> {
      final var thread = new Thread(runnable, threadName);
      thread.setDaemon(true);
      return thread;
    });

  }

  /**
   * The name under which a claim of this node is written. It stays the same while the
   * application runs, and it tells this run apart from an earlier one of the same process.
   * So a node which died and came back does not renew what it held before.
   *
   * @return Which node holds a claim, written into the entry
   */
  public String owner() {

    return owner;

  }

  /**
   * The end of a lease which starts now. A claim and every renewal write it onto the entry.
   * It is computed again for every write, so the answer moves with the clock, and not with
   * the moment the dispatcher was built.
   *
   * @return When a lease taken now runs out
   */
  public Instant endsAt() {

    return Instant.now().plus(lease);

  }

  /**
   * Starts renewing the lease of one entry until the returned handle is closed. The
   * dispatcher opens it where it claims the entry. So the renewal covers the wait for a
   * thread as well as the dispatch itself.
   *
   * @param entryId The entry being dispatched
   * @param renewal What one tick writes
   * @return The handle the dispatch closes when it is done
   */
  public Held renewWhile(
      final String entryId,
      final Renewal renewal) {

    final var schedule = new RenewalSchedule(entryId, renewal);
    schedule.start();
    return schedule;

  }

  /**
   * Stops renewing anything. It is called when the dispatcher stops. What a lane still held
   * keeps its lease until the lease runs out, and then another node takes the entry.
   */
  public void stop() {

    ticks.shutdownNow();

  }

  /**
   * The ticking of one entry.
   */
  private class RenewalSchedule implements Held {

    private final String entryId;

    private final Renewal renewal;

    /**
     * The next tick, cancelled when the dispatch is done. The thread which dispatches writes
     * it, and the thread which renews reads it.
     */
    private volatile ScheduledFuture<?> nextTick;

    private volatile boolean done;

    private RenewalSchedule(
        final String entryId,
        final Renewal renewal) {

      this.entryId = entryId;
      this.renewal = renewal;

    }

    private void start() {

      nextTick = ticks.schedule(this::renewAndTickAgain, tick.toMillis(), TimeUnit.MILLISECONDS);

    }

    private void renewAndTickAgain() {

      if (done) {
        return;
      }
      try {
        if (!renewal.renewUntil(entryId, endsAt())) {
          done = true;
          log
              .warn(
                  "The lease of the phase-two outbox entry '{}' was lost while it was being "
                      + "dispatched - another node may be carrying out the same operation now. The "
                      + "dispatch here runs to its end",
                  entryId);
          return;
        }
      } catch (final RuntimeException | Error e) {
        // a scheduled task which lets anything escape is never run again, and the executor
        // keeps the reason to itself. This entry would stop being renewed, and nothing would
        // say so
        log.error("Could not renew the lease of the phase-two outbox entry '{}' - trying again", entryId, e);
      }
      if (!done) {
        start();
      }

    }

    @Override
    public void close() {

      done = true;
      final var scheduled = nextTick;
      if (scheduled != null) {
        scheduled.cancel(false);
      }

    }

  }

  /**
   * How often the lease of a running dispatch is written again: every third of it. So if one
   * renewal does not get through, two more come before the lease runs out.
   *
   * @param lease How long a claim lasts
   * @return The distance between two renewals
   */
  private static Duration tickOf(
      final Duration lease) {

    final var third = lease.dividedBy(3);
    return third.compareTo(SHORTEST_TICK) < 0 ? SHORTEST_TICK : third;

  }

  /**
   * The name which this node writes into the entries it claims. It is cut to the 255
   * characters of the column. A host name which nobody can read is left out, not
   * guessed.
   *
   * @return Host, process and a tail telling two runs apart
   */
  private static String ownerOfThisNode() {

    final var name = ManagementFactory
        .getRuntimeMXBean()
        .getName();
    final var owner = "%s/%s".formatted(
        name,
        UUID
            .randomUUID()
            .toString()
            .substring(0, 8));
    return owner.length() <= 255 ? owner : owner.substring(owner.length() - 255);

  }

}
