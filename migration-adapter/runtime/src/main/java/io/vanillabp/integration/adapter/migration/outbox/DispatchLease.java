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
 * The claim of one node on one outbox entry: who holds it and until when. A poll writes
 * both onto the entry, and no other poll - here or on another node - takes an entry whose
 * lease has not run out.
 * <p>
 * The lease is renewed WHILE the dispatch runs, which is the whole reason this class
 * exists. A dispatch calls a BPMS over the network and may take longer than any distance
 * chosen in advance; before the renewal, such a dispatch simply lost its entry to the next
 * poll and the operation was carried out twice. Each renewal is a short transaction of its
 * own - one <code>UPDATE</code> per tick, on a connection borrowed and given back - so
 * nothing holds a connection for the length of a dispatch.
 * <p>
 * The tick is a third of the lease, which leaves two renewals before it would run out, and
 * it never goes below {@link #SHORTEST_TICK}: an application which sets
 * <code>vanillabp.outbox.attempt-frequency</code> to a very small value gets a short lease
 * and not a loop of updates. One daemon thread carries the ticks of all entries a node
 * dispatches at once, because a renewal is a single write by primary key - a database too
 * slow to answer that within a third of the lease is one the dispatch itself is not getting
 * through either.
 * <p>
 * A renewal which matches no row means the lease was lost: the entry was taken over, or it
 * was finished by somebody else. The ticking stops there and says so, because from that
 * moment on two nodes may be carrying out the same operation, which is exactly what an
 * operator has to be able to read afterwards.
 * <p>
 * What the lease does NOT do is count. The number of attempts is written when an attempt
 * ENDED, so an entry whose dispatch is slow uses up no attempt budget (see
 * <code>vanillabp.outbox.block-after-attempts</code>).
 */
@Slf4j
public class DispatchLease {

  /**
   * The shortest distance between two renewals of one entry. It guards against a lease so
   * short that renewing it would be a loop of writes rather than a heartbeat.
   */
  static final Duration SHORTEST_TICK = Duration.ofMillis(50);

  /**
   * Which node holds a claim, as the row shows it during support. The host and the process
   * are what an operator recognises, and the random tail is what tells two runs of the same
   * process apart: a node which died and came back must not renew the lease it held before.
   */
  private final String owner;

  private final Duration lease;

  private final Duration tick;

  private final ScheduledExecutorService ticks;

  /**
   * What one tick does: writes the new end of the lease onto the entry, in a transaction of
   * its own.
   */
  @FunctionalInterface
  public interface Renewal {

    /**
     * Writes the new end of the lease onto one entry, as its own short transaction. The
     * dispatcher supplies it, because only it knows the table or the collection the entry
     * lies in.
     *
     * @param entryId The entry whose lease is being renewed
     * @param leaseEnd The moment the lease is to run until
     * @return Whether this node still holds the lease - <code>false</code> where the write
     *         matched no row, which is the entry being gone or held by somebody else
     */
    boolean renewUntil(
        String entryId,
        Instant leaseEnd);

  }

  /**
   * A lease being renewed. Closing it ends the ticking, which every dispatch does whether
   * it succeeded or threw.
   */
  public interface Held extends AutoCloseable {

    @Override
    void close();

  }

  /**
   * Builds the renewal of one dispatcher: the name this node writes into the entries it
   * claims, and the one daemon thread which carries the ticks of every entry that
   * dispatcher holds at the same time.
   *
   * @param threadName The name of the daemon thread renewing, which is what an operator
   *          reads in a thread dump
   * @param lease How long a claim lasts - <code>vanillabp.outbox.attempt-frequency</code>,
   *          which is also the distance a failed dispatch waits before it is read again
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
   * The name a claim of this node is written under. It stays the same while the
   * application runs and tells this run apart from an earlier one of the same process, so
   * a node which died and came back does not renew what it held before.
   *
   * @return Which node holds a claim, written into the entry
   */
  public String owner() {

    return owner;

  }

  /**
   * The end of a lease starting now, which a claim and every renewal write onto the entry.
   * Asked anew for every write, so the answer moves with the clock rather than with the
   * moment the dispatcher was built.
   *
   * @return When a lease taken now runs out
   */
  public Instant endsAt() {

    return Instant.now().plus(lease);

  }

  /**
   * Starts renewing the lease of one entry until the returned handle is closed.
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
   * Stops renewing anything. Called when the dispatcher stops - what a lane was still
   * holding keeps its lease until it runs out, and another node takes the entry then.
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
     * The next tick, cancelled when the dispatch is done. Written by the thread which
     * dispatches and read by the thread which renews.
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
                      + "dispatch here runs to its end, and whichever of the two finishes last writes "
                      + "the result",
                  entryId);
          return;
        }
      } catch (final RuntimeException | Error e) {
        // a scheduled task which lets anything escape is never run again, and the executor
        // keeps the reason to itself: this entry would stop being renewed and nothing would
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
   * How often the lease of a running dispatch is written anew: a third of it, so a renewal
   * which does not get through leaves two more before the lease runs out.
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
   * The name this node writes into the entries it claims. Bounded to the 255 characters the
   * column holds, and a host name nobody can read is left out rather than guessed.
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
