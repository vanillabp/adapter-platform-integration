package io.vanillabp.integration.outbox.gruelbox;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

import com.gruelbox.transactionoutbox.TransactionOutbox;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.outbox.DueEntryPoller;
import io.vanillabp.integration.deployment.SpringBootDeploymentService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Background processing of the gruelbox transaction outbox: right after a commit
 * gruelbox dispatches the scheduled call itself, but for crash recovery and retries a
 * poller calling {@link TransactionOutbox#flush()} is required. Flushing
 * also deletes successfully dispatched entries whose retention threshold passed (the
 * asynchronous cleanup of the "DONE instead of delete" contract). The poller is
 * started on {@link ApplicationReadyEvent} (the first run also dispatches entries
 * left over from a previous crashed instance).
 * <p>
 * It does not flush on a rhythm. A flush is three database commands whether or not
 * anything is waiting, so between two of them the poller sleeps until the moment
 * gruelbox' own table says the next entry wants something
 * ({@link GruelboxPhaseTwoOutbox#earliestDueAt()}), bounded by
 * <code>vanillabp.outbox.poll-interval</code> for the one case nothing can be read from
 * the table: work another node wrote down before it died (see {@link DueEntryPoller}).
 * <p>
 * Starting the poller is also what lets an outbox built with VanillaBP's
 * {@link GruelboxRedispatchAwareSubmitter} dispatch after a commit at all. That
 * submitter keeps its entries until this dispatcher runs. A workflow started while
 * Spring Boot answers requests and the models are still on their way to the BPMS
 * therefore waits for the first poll, instead of reaching a BPMS which cannot know
 * it.
 * <p>
 * The poller runs on a private single-thread daemon executor - no
 * {@link org.springframework.scheduling.TaskScheduler} bean is registered or used, so
 * an application's own scheduling setup (e.g. <code>&#64;EnableScheduling</code>)
 * stays unaffected.
 * <p>
 * <strong>How this store holds an entry while it dispatches it.</strong> The stores VanillaBP
 * owns lease an entry and renew the lease while the dispatch runs
 * ({@link io.vanillabp.integration.adapter.migration.outbox.DispatchLease}), so a slow
 * dispatch is never carried out twice and costs no attempt. Gruelbox does it differently and
 * needs nothing added: its flush selects with <code>FOR UPDATE SKIP LOCKED</code> and then
 * locks the row it is about to invoke for, so the flush of another node skips an entry
 * somebody is dispatching however long that takes. The price is the one VanillaBP's own
 * stores avoid - the lock is a database transaction held for the length of the dispatch, and
 * therefore a connection - and it is gruelbox' design, not something this dispatcher chooses.
 * <p>
 * What it does mean is that gruelbox counts an attempt when it takes an entry rather than
 * when the attempt ended. Since nobody else can take that entry meanwhile, the two numbers
 * are the same here, and an entry only looks attempted while it is still travelling. The
 * table belongs to gruelbox, so this is written down rather than changed.
 * <p>
 * A dispatch which knows that repeating helps in a moment says so
 * ({@link io.vanillabp.integration.spi.PhaseTwoRetryLater} - a workflow its BPMS has not
 * made searchable yet), and gruelbox would schedule the next attempt from the
 * <code>attemptFrequency</code> of the whole outbox. The window the dispatch named is
 * written over it by {@link GruelboxPhaseTwoFailureListener}, so such an entry is due here
 * when it is due on the stores VanillaBP wrote itself. It is dispatched at the poll after
 * that moment, and the poller above sleeps until the earliest due moment or the configured
 * cap, whichever comes first ({@link DueEntryPoller}), so the delay this store adds to the
 * window is at most <code>vanillabp.outbox.poll-interval</code>. Nothing waits on any
 * thread for it, so the entries of every other workflow are dispatched while that one is
 * due again.
 */
@Slf4j
public class GruelboxPhaseTwoOutboxDispatcher {

  private final TransactionOutbox transactionOutbox;

  /**
   * The store this dispatcher polls, which is what answers when the next flush has
   * something to do. <code>null</code> for a caller which did not hand one over - the
   * poller then keeps to the configured cap, the rhythm every application had before the
   * sleeping was there.
   */
  private final GruelboxPhaseTwoOutbox outbox;

  /**
   * The submitter whose gate is opened when polling starts, <code>null</code> for an
   * outbox built with a submitter of somebody else's.
   */
  private final GruelboxRedispatchAwareSubmitter submitter;

  private final DueEntryPoller poller;

  /**
   * How long a payload nobody removed is kept, and where those are removed from.
   * <code>null</code> for a dispatcher built without a payload store - nothing is
   * house-kept then, and the same holds for a dispatcher without the store above, which
   * is what answers which payloads the entries still name.
   */
  private final io.vanillabp.integration.spi.PhaseTwoPayloadStore payloadStore;

  private final java.time.Duration retention;

  /**
   * Polls an outbox which dispatches right after a commit, whoever built it. Use the
   * constructor taking VanillaBP's submitter to have the entries of the window before
   * the deployment wait for the first poll.
   *
   * @param transactionOutbox The outbox to poll
   * @param properties The bound <code>vanillabp.outbox</code> section
   */
  public GruelboxPhaseTwoOutboxDispatcher(
      final TransactionOutbox transactionOutbox,
      final PhaseTwoOutboxProperties properties) {

    this(transactionOutbox, properties, null, null, null);

  }

  /**
   * Polls the outbox and holds its submitter back until it does. Building this
   * dispatcher is what closes the submitter's gate, so a submitter never waits for a
   * dispatcher which does not exist (see
   * {@link GruelboxRedispatchAwareSubmitter}).
   *
   * @param transactionOutbox The outbox to poll
   * @param properties The bound <code>vanillabp.outbox</code> section
   * @param submitter The submitter the outbox was built with
   * @param outbox The store, asked when the next flush has something to do
   */
  public GruelboxPhaseTwoOutboxDispatcher(
      final TransactionOutbox transactionOutbox,
      final PhaseTwoOutboxProperties properties,
      final GruelboxRedispatchAwareSubmitter submitter,
      final GruelboxPhaseTwoOutbox outbox) {

    this(transactionOutbox, properties, submitter, outbox, null);

  }

  /**
   * Polls the outbox, holds its submitter back until it does and house-keeps the
   * payloads nobody removed.
   *
   * @param transactionOutbox The outbox to poll
   * @param properties The bound <code>vanillabp.outbox</code> section
   * @param submitter The submitter the outbox was built with
   * @param outbox The store, asked when the next flush has something to do
   * @param payloadStore Where the payloads of this outbox lie
   */
  public GruelboxPhaseTwoOutboxDispatcher(
      final TransactionOutbox transactionOutbox,
      final PhaseTwoOutboxProperties properties,
      final GruelboxRedispatchAwareSubmitter submitter,
      final GruelboxPhaseTwoOutbox outbox,
      final io.vanillabp.integration.spi.PhaseTwoPayloadStore payloadStore) {

    this.transactionOutbox = transactionOutbox;
    this.submitter = submitter;
    this.outbox = outbox;
    this.payloadStore = payloadStore;
    this.retention = properties.getRetention();
    this.poller = new DueEntryPoller(
        "vanillabp-outbox", properties.getPollInterval(), this::flush, this::earliestDueAt);
    if (submitter != null) {
      submitter.holdBackUntilDispatchingStarted();
    }

  }

  /**
   * When the next flush has something to do, or <code>null</code> where the store cannot
   * say.
   *
   * @return The moment of the earliest entry gruelbox still owes something to
   */
  private java.time.Instant earliestDueAt() {

    return outbox == null ? null : outbox.earliestDueAt();

  }

  /**
   * Starts the fixed-delay poller. The first run is executed immediately, dispatching
   * committed-but-unprocessed entries of a previously crashed instance and those the
   * submitter kept while the application was starting. The listener
   * order guarantees that workflow processing started BEFORE any recovered entry is
   * dispatched (see
   * {@link SpringBootDeploymentService#OUTBOX_DISPATCHER_LISTENER_ORDER}).
   */
  @Order(SpringBootDeploymentService.OUTBOX_DISPATCHER_LISTENER_ORDER)
  @EventListener(ApplicationReadyEvent.class)
  public void startPolling() {

    // opened before the poller starts, because a flush hands what it picked up to
    // this same submitter: a gate still closed would keep those entries, and each of
    // them would be due again only after 'attempt-frequency' instead of at once
    if (submitter != null) {
      submitter.dispatchingStarted();
    }
    poller.start();

  }

  @PreDestroy
  public void stopPolling() {

    poller.stop();

  }

  /**
   * Flushes the outbox until no more work is done. Exceptions are caught to keep the
   * poller alive.
   */
  private void flush() {

    try {
      //noinspection StatementWithEmptyBody
      while (transactionOutbox.flush()) {
        // repeat until all due outbox entries were processed
      }
    } catch (Exception e) {
      log.error("Flushing the VanillaBP phase-two outbox failed - will retry", e);
    }
    // the payloads of the entries the flush deleted, and what a crash between the two
    // writes of a schedule left behind. What an entry still names stays with it,
    // whether that entry waits or is blocked, and this flush was going to happen anyway
    if ((payloadStore != null) && (outbox != null)) {
      payloadStore.removeOrphansOlderThan(java.time.Instant.now().minus(retention), outbox::stillNaming);
    }

  }

}
