package io.vanillabp.integration.outbox.mongo;

import java.time.Duration;
import java.time.Instant;
import java.util.OptionalLong;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.DispatchOutcome;
import io.vanillabp.integration.adapter.migration.outbox.AStoppingNode;
import io.vanillabp.integration.adapter.migration.outbox.DispatchLanes;
import io.vanillabp.integration.adapter.migration.outbox.DispatchLease;
import io.vanillabp.integration.adapter.migration.outbox.DueEntryPoller;
import io.vanillabp.integration.adapter.migration.outbox.LastFailure;
import io.vanillabp.integration.adapter.migration.outbox.OutboxHousekeeping;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.deployment.SpringBootDeploymentService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoPermanentFailure;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches the entries of the MongoDB-based phase-two outbox which were committed but
 * not processed yet. It hands them to the core's {@link PhaseTwoRouter}:
 * <ul>
 * <li>right after a commit (triggered by {@link MongoPhaseTwoOutbox}), and</li>
 * <li>by a poller (crash recovery and retries) started on {@link ApplicationReadyEvent}.
 * The poller does not poll on a rhythm. It sleeps until the earliest entry it still owes
 * something is due. For work which another node wrote down before it died, the sleep is
 * bounded by <code>vanillabp.outbox.poll-interval</code> (see {@link DueEntryPoller}).</li>
 * </ul>
 * Due entries (status {@link PhaseTwoOutboxEntry#STATUS_OPEN}) are claimed atomically.
 * A find-and-modify writes <code>leasedBy</code> and <code>leasedUntil</code>, and the
 * lease lasts one <code>vanillabp.outbox.attempt-frequency</code>. So several instances do
 * not dispatch the same entry at the same time. <strong>A running dispatch renews its
 * lease</strong> ({@link DispatchLease}). So an entry which travels longer than that stays
 * with the node which carries it, and the next poll does not take it. A node which dies
 * stops renewing, and another node takes the entry over once the lease ran out. Every
 * write which says how an attempt ended names the holder of the lease. So a node which
 * lost its entry writes nothing over what the node holding it wrote.
 * <p>
 * A dispatch which FAILS writes the next attempt itself. The distance to it grows as
 * {@link io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties#attemptDelay(int)}
 * says. It doubles per attempt, up to <code>vanillabp.outbox.max-attempt-frequency</code>.
 * After a successful dispatch, the entry is marked {@link PhaseTwoOutboxEntry#STATUS_DONE}.
 * It stays in the collection for support to read. Its <code>dedupKey</code> is replaced by
 * its own id, so a repetition of the same operation can be planned again. It is deleted
 * in the background once <code>vanillabp.outbox.retention</code> passed. After
 * <code>vanillabp.outbox.block-after-attempts</code> failed attempts, an entry is marked
 * {@link PhaseTwoOutboxEntry#STATUS_BLOCKED} and waits for a person, see
 * {@link PhaseTwoOutboxProperties#BLOCKED_ENTRIES_GUIDE}.
 * <p>
 * <strong>An entry says why its last attempt did not get through.</strong> Every write
 * which ends an attempt sets <code>lastFailure</code>. It holds the reason where the attempt
 * failed or was answered with "not yet", and nothing where it went through.
 * {@link io.vanillabp.integration.adapter.migration.outbox.LastFailure} builds the text, so
 * it reads the same as the column of the JDBC table.
 * <p>
 * <strong>Waiting for a read model is not an attempt.</strong> A dispatch which the adapter
 * answers with {@link io.vanillabp.integration.spi.PhaseTwoRetryLater} is written back with
 * the window the adapter named, and <code>attempts</code> stays as it was. Time ends that
 * wait. Once <code>vanillabp.outbox.wait-for-visibility-at-most</code> passed since the
 * entry was written, the entry is blocked (see decision 113 in the repository's
 * DECISIONS.md).
 * <p>
 * <strong><code>attempts</code> counts attempts, not claims.</strong> The field is written
 * when an attempt ENDED, together with what became of the entry. So a dispatch which takes
 * its time uses up no attempt budget.
 * <p>
 * The poller runs on a private single-thread daemon executor. No
 * {@link org.springframework.scheduling.TaskScheduler} bean is registered or used. So an
 * application's own scheduling setup (e.g. <code>&#64;EnableScheduling</code>) is not
 * affected.
 */
@Slf4j
public class MongoPhaseTwoOutboxDispatcher implements OutboxHousekeeping.Store {

  private final MongoTemplate mongoTemplate;

  private final ObjectProvider<PhaseTwoRouter> phaseTwoRouter;

  private final PhaseTwoOutboxProperties properties;

  /**
   * The collection which is polled for outbox entries. Its {@link MongoPhaseTwoOutbox}
   * writes to the same one.
   */
  private final String collection;

  /**
   * What a blocked entry is counted into. It is a provider and not the bean itself,
   * because Micrometer is optional, and the application may bring no metrics at all.
   */
  private final ObjectProvider<VanillaBpMetrics> metrics;

  private final DueEntryPoller poller;

  /**
   * The threads the entries are dispatched on, and the rule which says that the entries of
   * one workflow aggregate share one of them.
   */
  private final DispatchLanes lanes;

  /**
   * What a running dispatch holds its entry with, and what keeps that hold alive while it
   * runs.
   */
  private final DispatchLease lease;

  /**
   * Where the bytes of a call which carries a payload lie while its entry waits.
   */
  private final MongoPhaseTwoPayloadStore payloadStore;

  /**
   * Where this node says that it is house-keeping this store tonight. So no other node
   * measures its work at the same time.
   */
  private final MongoHousekeepingLease housekeepingLease;

  /**
   * What removes the dispatched entries and the orphaned payloads, and when.
   */
  private final OutboxHousekeeping housekeeping;

  /**
   * Builds the dispatcher together with its payload store, its lease and its poller. The
   * poller does not run yet. It starts once workflow processing has started. So nothing is
   * carried to a BPMS which has not seen the models.
   *
   * @param mongoTemplate The template writing and reading the entries
   * @param phaseTwoRouter Provider of the router dispatched to
   * @param properties The bound <code>vanillabp.outbox</code> section
   * @param collection The collection polled
   * @param metrics Provider of what a blocked entry is counted into
   */
  public MongoPhaseTwoOutboxDispatcher(
      final MongoTemplate mongoTemplate,
      final ObjectProvider<PhaseTwoRouter> phaseTwoRouter,
      final PhaseTwoOutboxProperties properties,
      final String collection,
      final ObjectProvider<VanillaBpMetrics> metrics) {

    this.mongoTemplate = mongoTemplate;
    this.phaseTwoRouter = phaseTwoRouter;
    this.properties = properties;
    this.collection = collection;
    this.metrics = metrics;
    this.payloadStore = new MongoPhaseTwoPayloadStore(
        mongoTemplate, properties.getMongo().payloadCollectionName(), collection);
    this.poller = new DueEntryPoller(
        "vanillabp-outbox", properties.getPollInterval(), this::poll, this::earliestDueAt);
    this.lanes = new DispatchLanes("vanillabp-outbox-dispatch", properties.getDispatchThreads());
    this.lease = new DispatchLease("vanillabp-outbox-lease", properties.getAttemptFrequency());
    this.housekeepingLease = new MongoHousekeepingLease(
        mongoTemplate, properties
            .getMongo()
            .getHousekeepingCollection());
    this.housekeeping = new OutboxHousekeeping(
        this, properties, () -> metrics.getIfAvailable(() -> VanillaBpMetrics.NONE));

  }

  /**
   * When this store owes something: the due time of the earliest entry which waits for its
   * dispatch, or the moment the oldest dispatched entry may be deleted, whichever comes
   * first. A BLOCKED entry is in neither set. It waits for a person, not for a clock, so it
   * must not keep the poller awake.
   * <p>
   * An entry which is being dispatched right now answers with the end of its lease, not
   * with "due". The claim and every renewal push <code>nextAttemptAt</code> along with
   * <code>leasedUntil</code>. Without that, the poller would be told "due now" for as long
   * as the dispatch runs, and it would ask again at its shortest sleep.
   *
   * @return The earliest of the two moments, or <code>null</code> where the collection
   *         holds neither
   */
  private Instant earliestDueAt() {

    final var nextAttempt = earliest(
        Query
            .query(Criteria
                .where("status")
                .is(PhaseTwoOutboxEntry.STATUS_OPEN)
                .and("attempts")
                .lt(properties.getBlockAfterAttempts())),
        "nextAttemptAt");
    final var oldestDone = earliest(
        Query
            .query(Criteria
                .where("status")
                .is(PhaseTwoOutboxEntry.STATUS_DONE)),
        "doneAt");
    final var retentionRunsOut = oldestDone == null
        ? null
        : oldestDone.plus(properties.getRetention());
    if (nextAttempt == null) {
      return retentionRunsOut;
    }
    if (retentionRunsOut == null) {
      return nextAttempt;
    }
    return nextAttempt.isBefore(retentionRunsOut) ? nextAttempt : retentionRunsOut;

  }

  /**
   * The smallest value of one field among the documents a query matches. It is read as one
   * document and not as an aggregation. So the index which this store creates over the
   * status and that field serves the sort.
   *
   * @param query What to look at
   * @param field The field to order by and to read
   * @return The value or <code>null</code> where nothing matches
   */
  private Instant earliest(
      final Query query,
      final String field) {

    query
        .with(Sort.by(Sort.Direction.ASC, field))
        .limit(1)
        .fields()
        .include(field);
    final var entry = mongoTemplate.findOne(query, PhaseTwoOutboxEntry.class, collection);
    if (entry == null) {
      return null;
    }
    return "doneAt".equals(field) ? entry.getDoneAt() : entry.getNextAttemptAt();

  }

  /**
   * Starts the poller. The first run starts at once. It dispatches the entries which a
   * crashed instance committed but did not process. The listener order makes sure that
   * workflow processing started BEFORE any recovered entry is dispatched (see
   * {@link io.vanillabp.integration.deployment.SpringBootDeploymentService#OUTBOX_DISPATCHER_LISTENER_ORDER}).
   */
  @Order(SpringBootDeploymentService.OUTBOX_DISPATCHER_LISTENER_ORDER)
  @EventListener(ApplicationReadyEvent.class)
  public void startPolling() {

    poller.start();
    housekeeping.start();

  }

  /**
   * Stops the poller, the lanes and the renewal of the leases. What a lane was still
   * holding stays OPEN in the collection. It keeps its lease until the lease runs out, and
   * then another node takes the entry.
   */
  @PreDestroy
  public void stopPolling() {

    poller.stop();
    lanes.stop();
    lease.stop();
    housekeeping.stop();

  }

  /**
   * Pulls the next poll forward to now. It is used right after a commit, where the entry
   * which was just written should go out at once.
   */
  public void triggerPoll() {

    poller.somethingIsDueAt(Instant.now());

  }

  /**
   * Claims all due entries and hands each of them to the lane of its aggregate, then deletes
   * DONE entries whose retention passed. Exceptions are caught to keep the poller alive.
   * <p>
   * This thread does the claim, and a lane does the dispatch. The order matters. An entry
   * is claimed before it is handed over. So the claim of the next poll, here or on another
   * node, finds it leased and leaves it alone.
   * <p>
   * A lane whose queue is full makes this thread wait. Waiting is the back pressure of a
   * backlog which arrives faster than it leaves. It keeps the backlog in the collection,
   * where it can be read.
   */
  private void poll() {

    try {
      while (true) {
        final var now = Instant.now();
        final var due = Query.query(Criteria
            .where("status")
            .is(PhaseTwoOutboxEntry.STATUS_OPEN)
            .and("nextAttemptAt")
            .lte(now)
            .and("attempts")
            .lt(properties.getBlockAfterAttempts())
            .orOperator(
                Criteria.where("leasedUntil").is(null),
                Criteria.where("leasedUntil").lte(now)));
        // the oldest entry first, because the lanes need that. They keep the order in which
        // they get the entries of one aggregate. A claim in whatever order the collection
        // happens to answer would hand them over the wrong way round. The index over the
        // status and that moment serves the sort
        due.with(Sort.by(Sort.Direction.ASC, "createdAt"));
        // claim the entry atomically: this node's name and the end of the lease. So other
        // instances skip it while the dispatch runs and renews. Only a free lease makes an
        // entry available, never the number of attempts, which says what ENDED
        final var leaseEnds = lease.endsAt();
        final var claim = new Update()
            .set("leasedBy", lease.owner())
            .set("leasedUntil", leaseEnds)
            .set("nextAttemptAt", leaseEnds);
        final var entry = mongoTemplate.findAndModify(
            due, claim, PhaseTwoOutboxEntry.class, collection);
        if (entry == null) {
          break;
        }
        handOverToItsLane(entry);
      }
    } catch (Exception e) {
      log.error("Polling the VanillaBP phase-two outbox failed - will retry", e);
    }

  }

  /**
   * Pushes along the lease of an entry which this node is dispatching. It is a write of
   * its own, once per tick of {@link DispatchLease}.
   *
   * @param entryId The entry being dispatched
   * @param leaseEnd How long the lease is to last now
   * @return Whether this node still holds the entry. <code>false</code> where the write
   *         matched no document: the entry is gone, or somebody else holds it
   */
  private boolean renewLease(
      final String entryId,
      final Instant leaseEnd) {

    try {
      return mongoTemplate
          .updateFirst(
              Query
                  .query(Criteria
                      .where("_id")
                      .is(entryId)
                      .and("leasedBy")
                      .is(lease.owner())
                      .and("status")
                      .is(PhaseTwoOutboxEntry.STATUS_OPEN)),
              new Update()
                  .set("leasedUntil", leaseEnd)
                  .set("nextAttemptAt", leaseEnd),
              collection)
          .getMatchedCount() == 1;
    } catch (final RuntimeException e) {
      // a collection which cannot be asked says nothing about who holds the entry. So the
      // renewal keeps trying, and does not give the entry up over one hiccup
      log.warn("Could not renew the lease of the phase-two outbox entry '{}' - trying again", entryId, e);
      return true;
    }

  }

  /**
   * Hands the entry which the poll has just claimed to the lane of its aggregate. The
   * renewal of its lease is already running.
   * <p>
   * The renewal starts here and not where the lane picks the entry up. Between the two, the
   * entry waits in the lane's queue. A wait longer than the lease would let another node
   * claim an entry which this one is about to dispatch. Then both would carry the operation
   * out.
   *
   * @param entry The entry this node has just claimed
   */
  private void handOverToItsLane(
      final PhaseTwoOutboxEntry entry) {

    final var held = lease.renewWhile(entry.getId(), this::renewLease);
    try {
      lanes.runInOrderOf(orderingKeyOf(entry), () -> dispatch(entry, held));
    } catch (final RuntimeException | Error e) {
      // nothing will dispatch this entry, so nothing would close the renewal either
      held.close();
      throw e;
    }

  }

  /**
   * What an entry is ordered by: its workflow aggregate. An entry which names none, like a
   * broadcast signal, is ordered by its BPMN process instead. So two signals of one process
   * still leave in the order they were planned.
   *
   * @param entry The entry about to be dispatched
   * @return The key deciding which lane dispatches it
   */
  private static String orderingKeyOf(
      final PhaseTwoOutboxEntry entry) {

    return entry.getAggregateId() == null
        ? "%s|%s".formatted(entry.getWorkflowModuleId(), entry.getBpmnProcessId())
        : "%s|%s|%s".formatted(entry.getWorkflowModuleId(), entry.getBpmnProcessId(), entry.getAggregateId());

  }

  /**
   * Dispatches a single claimed entry through the core's {@link PhaseTwoRouter}. On
   * success the entry is marked DONE; on failure it stays claimed and is retried
   * after the configured backoff, until it is blocked.
   * <p>
   * The renewal ends here, before the write which says how the attempt ended. That write
   * needs a lease which has not run out, and the last renewal gave it one. It does not need
   * a lease which is still growing. And a renewal which outlives the write would find the
   * entry no longer OPEN, and it would report a lease it never lost.
   *
   * @param entry The claimed entry (holding the state before it was claimed)
   * @param held The renewal which started with the claim, closed when this attempt is over
   */
  private void dispatch(
      final PhaseTwoOutboxEntry entry,
      final DispatchLease.Held held) {

    final String payloadReference;
    // everything the attempt does is inside, including reading the arguments of the
    // entry. So the renewal is let go, whichever way the attempt ends
    try (held) {
      // the dispatch of an entry which was taken before may have reached the BPMS already
      // (recovered or retried). Then the router runs the START re-dispatch mitigation. The
      // operation travels as its persisted name, and the router's operation registry
      // resolves it.
      // The payload costs one extra read, and only for an entry which names a payload: a
      // lookup by _id, once per dispatch attempt
      payloadReference = entry.getArgs() == null
          ? null
          : entry.getArgs().get(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
      final var payload = payloadReference == null
          ? null
          : payloadStore.read(payloadReference);
      dispatchMeasuringTheWait(
          PhaseTwoCall
              .forDispatch(
                  entry.getOperation(), entry.getWorkflowModuleId(), entry.getBpmnProcessId(), entry
                      .getAggregateId(),
                  entry.getAdapterId(), entry.getArgs(), payload),
          wasTakenBefore(entry),
          entry.getCreatedAt());
    } catch (final Exception e) {
      reportFailedDispatch(entry, e);
      return;
    }
    final var markedDone = new Update()
        .set("status", PhaseTwoOutboxEntry.STATUS_DONE)
        .set("doneAt", Instant.now())
        // the deduplication window ends with the dispatch. The entry's own id takes the
        // place of the key. The key stays readable in idempotencyKey
        .set("dedupKey", entry.getId())
        // the attempt is counted where it ended, and the lease is given back
        .inc("attempts", 1)
        .unset("leasedBy")
        .unset("leasedUntil")
        // the attempt which ended last went through, so there is no failure to name
        .unset("lastFailure");
    try {
      if (!writeAsTheHolder(entry, markedDone)) {
        // the entry belongs to another node, and so do its bytes. That node may still be
        // dispatching, and it would find a payload which is gone
        return;
      }
    } catch (final RuntimeException e) {
      // the dispatch got through and the mark did not, so the entry says nothing about what
      // happened. It is planned again, which repeats the operation. That is the
      // at-least-once which the contract names. It is said out loud, through the same
      // report a failed dispatch uses. A mark lost in silence leaves an operation which
      // looks undone, with nobody to ask
      reportFailedDispatch(entry, e);
      return;
    }
    // the entry is dispatched, so its bytes have done their work. They are removed AFTER
    // the entry was marked, never before. A crash in between leaves a document which the
    // housekeeping deletes. The other order would leave an entry whose payload is gone
    if (payloadReference != null) {
      payloadStore.remove(payloadReference);
    }

  }

  /**
   * Writes down what a failed dispatch means for the entry. A write which cannot get
   * through stays inside this lane.
   * <p>
   * The mark path is guarded the same way. What the store throws there ends as a report.
   * It does not end as an exception which leaves the runnable of a lane. There it would be
   * printed as "Exception in thread vanillabp-outbox-dispatch-1", with a stack trace nobody
   * can place. The failure path used to be the one which was not guarded. A shutdown during
   * a dispatch is exactly when that matters.
   *
   * @param entry The entry whose dispatch failed
   * @param e What the dispatch threw
   */
  private void reportFailedDispatch(
      final PhaseTwoOutboxEntry entry,
      final Exception e) {

    try {
      writeDownTheFailedDispatch(entry, e);
    } catch (final RuntimeException whileWritingItDown) {
      reportAResultWhichWasNotWritten(entry, whileWritingItDown);
    }

  }

  /**
   * Says that how an attempt ended was not written down, and says which of the two cases
   * it is.
   * <p>
   * Nothing is lost either way. The entry stays as it was and keeps its lease. The next
   * node to pick it up dispatches it again once that lease runs out. That is the
   * at-least-once which the contract names. What differs is who has to do something. A
   * node which is being STOPPED is the ordinary case of
   * {@code JdbcPhaseTwoOutboxDispatcher#reportTheEntryNoLaneTook}. It is said at INFO,
   * because a stack trace there sends an operator to look for a fault where there was only
   * a deployment. Everything else stays as sharp as it was.
   * <p>
   * The interrupt flag is set again before the line is written. The work of this lane is
   * over, and whoever asks the thread next has to see that it was interrupted.
   *
   * @param entry The entry this attempt ran on
   * @param e What the store threw while the result was being written
   */
  private void reportAResultWhichWasNotWritten(
      final PhaseTwoOutboxEntry entry,
      final RuntimeException e) {

    if (AStoppingNode.isTheReasonFor(e)) {
      Thread.currentThread().interrupt();
      log.info(
          "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' was "
              + "interrupted because this node is stopping - the outbox entry '{}' stays as it is and is "
              + "dispatched once its lease runs out",
          entry.getOperation(),
          entry.getBpmnProcessId(),
          entry.getWorkflowModuleId(),
          entry.getAggregateId(),
          entry.getId());
      return;
    }
    log.error(
        "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
            + "failed, and how it failed could not be written down either - the outbox entry '{}' stays "
            + "as it is and is dispatched once its lease runs out!",
        entry.getOperation(),
        entry.getBpmnProcessId(),
        entry.getWorkflowModuleId(),
        entry.getAggregateId(),
        entry.getId(),
        e);

  }

  /**
   * Writes down what a failed dispatch means for the entry. It is blocked where repeating
   * cannot help, where the attempts are used up, or where the entry waited too long for its
   * BPMS. Otherwise it gets a new due time.
   *
   * @param entry The entry whose dispatch failed
   * @param e What the dispatch threw
   */
  private void writeDownTheFailedDispatch(
      final PhaseTwoOutboxEntry entry,
      final Exception e) {

    // the adapter said that repeating cannot help. So the entry is blocked right away,
    // and not after the configured attempts
    if (PhaseTwoPermanentFailure.isPermanent(e)) {
      if (!writeAsTheHolder(entry, blockEntry(entry.getId(), LastFailure.of(e)))) {
        return;
      }
      countBlockedEntry(entry.getOperation(), true);
      log.error(
          "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "failed for a reason repeating cannot fix - the outbox entry '{}' is blocked. How to find "
              + "it, open it again or delete it: "
              + PhaseTwoOutboxProperties.BLOCKED_ENTRIES_GUIDE,
          entry.getOperation(),
          entry.getBpmnProcessId(),
          entry.getWorkflowModuleId(),
          entry.getAggregateId(),
          entry.getId(),
          e);
      return;
    }
    final var retryAfter = PhaseTwoRetryLater.retryAfter(e);
    if (retryAfter != null) {
      waitForTheReadModel(entry, retryAfter, e);
      return;
    }
    if (entry.getAttempts() + 1 >= properties.getBlockAfterAttempts()) {
      if (!writeAsTheHolder(entry, blockEntry(entry.getId(), LastFailure.of(e)))) {
        return;
      }
      countBlockedEntry(entry.getOperation(), false);
      log.error(
          "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "failed {} times - the outbox entry '{}' is now blocked. How to find it, open it again or "
              + "delete it: "
              + PhaseTwoOutboxProperties.BLOCKED_ENTRIES_GUIDE,
          entry.getOperation(),
          entry.getBpmnProcessId(),
          entry.getWorkflowModuleId(),
          entry.getAggregateId(),
          entry.getAttempts() + 1,
          entry.getId(),
          e);
      return;
    }
    // the entry holds the attempts which ended before this one. So attemptDelay(0) is the
    // distance after the first failure. It is short, because most failures are
    // momentary
    final var retryIn = properties.attemptDelay(entry.getAttempts());
    if (!writeAsTheHolder(entry, dueAgainAt(Instant.now().plus(retryIn), LastFailure.of(e)))) {
      return;
    }
    log.warn(
        "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
            + "failed - the outbox entry '{}' is dispatched again in {} ({} of {} attempts used)",
        entry.getOperation(),
        entry.getBpmnProcessId(),
        entry.getWorkflowModuleId(),
        entry.getAggregateId(),
        entry.getId(),
        retryIn,
        entry.getAttempts() + 1,
        properties.getBlockAfterAttempts(),
        e);

  }

  /**
   * Writes down that the BPMS of the entry does not report its workflow yet. The entry
   * waits for the window the adapter named, not for the configured backoff. The attempt is
   * not counted, because a read model which is behind is no failure of the entry. A wait
   * which would never end is ended by the time since the entry was written:
   * <code>vanillabp.outbox.wait-for-visibility-at-most</code>.
   *
   * @param entry The entry whose dispatch was answered with "not yet"
   * @param retryAfter The window the adapter named
   * @param e What the dispatch threw
   */
  private void waitForTheReadModel(
      final PhaseTwoOutboxEntry entry,
      final Duration retryAfter,
      final Exception e) {

    final var now = Instant.now();
    if (properties.hasWaitedForVisibilityLongEnough(entry.getCreatedAt(), now)) {
      final var reason = LastFailure.ofAWaitWhichRanOut(Duration.between(entry.getCreatedAt(), now), e);
      if (!writeAsTheHolder(entry, blockEntry(entry.getId(), reason))) {
        return;
      }
      countBlockedEntry(entry.getOperation(), false);
      log.error(
          "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' waited {} "
              + "for its BPMS to report the workflow, which is longer than '{}' allows - the outbox "
              + "entry '{}' is now blocked. How to find it, open it again or delete it: "
              + PhaseTwoOutboxProperties.BLOCKED_ENTRIES_GUIDE
              + " - the last answer was: {}",
          entry.getOperation(),
          entry.getBpmnProcessId(),
          entry.getWorkflowModuleId(),
          entry.getAggregateId(),
          Duration.between(entry.getCreatedAt(), now),
          PhaseTwoOutboxProperties.WAIT_FOR_VISIBILITY_AT_MOST_PROPERTY,
          entry.getId(),
          e.getMessage());
      return;
    }
    if (!writeAsTheHolder(entry, dueAgainWithoutCountingAt(now.plus(retryAfter), LastFailure.of(e)))) {
      return;
    }
    log.info(
        "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' cannot "
            + "run yet - the outbox entry '{}' is dispatched again in {}, and it waits until {} at "
            + "most: {}",
        entry.getOperation(),
        entry.getBpmnProcessId(),
        entry.getWorkflowModuleId(),
        entry.getAggregateId(),
        entry.getId(),
        retryAfter,
        entry.getCreatedAt() == null
            ? null
            : entry.getCreatedAt().plus(properties.waitForVisibilityAtMost()),
        e.getMessage());

  }

  /**
   * Writes down how an attempt ended, on the entry this node holds. The name of this node
   * is in the condition, next to the id. So a node whose lease was taken over writes
   * nothing. The node which holds the entry carries out the same operation, and the
   * collection keeps its answer. The most expensive write to lose that race is a block over
   * an entry which the other node has just dispatched. Then somebody would be asked to
   * repair an operation which succeeded.
   *
   * @param entry The entry this attempt ran on
   * @param update What to write on it
   * @return Whether this node still held the entry, so that the write took effect
   */
  private boolean writeAsTheHolder(
      final PhaseTwoOutboxEntry entry,
      final Update update) {

    final var written = mongoTemplate.updateFirst(
        Query
            .query(Criteria
                .where("_id")
                .is(entry.getId())
                .and("leasedBy")
                .is(lease.owner())),
        update,
        collection);
    if (written.getMatchedCount() != 0) {
      return true;
    }
    reportResultOfALostEntry(entry);
    return false;

  }

  /**
   * Says that an attempt ended on an entry which this node does not hold any more. So what
   * it wanted to write was dropped.
   * <p>
   * The renewal said the same thing earlier, at the moment the entry changed hands. This
   * message is the other end of it, and it is worth its own line. It names the operation
   * which ran twice. And it proves that the second run did not overwrite what the node
   * which holds the entry wrote.
   *
   * @param entry The entry which was taken over while this node dispatched it
   */
  private void reportResultOfALostEntry(
      final PhaseTwoOutboxEntry entry) {

    log
        .warn(
            "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' ended, but "
                + "the outbox entry '{}' belongs to another node by now - the result of this dispatch was "
                + "dropped and the entry says what that node wrote",
            entry.getOperation(),
            entry.getBpmnProcessId(),
            entry.getWorkflowModuleId(),
            entry.getAggregateId(),
            entry.getId());

  }

  /**
   * Whether a dispatch has had this entry before. If so, a START probes the BPMS it was
   * meant for, and does not start a second workflow. An attempt which ended is counted. An
   * attempt whose node died in the middle left its name on the entry, and nothing else. The
   * claim answers with the document as it was BEFORE the claim. So that name is the one of
   * the node before this one.
   *
   * @param entry The claimed entry, as it stood before the claim
   * @return Whether an attempt ended or a holder disappeared
   */
  private static boolean wasTakenBefore(
      final PhaseTwoOutboxEntry entry) {

    return (entry.getAttempts() > 0) || (entry.getLeasedBy() != null);

  }

  /**
   * Hands the call to the core's router. Then it reports how long its entry waited for this
   * attempt, whether the attempt succeeded or threw. The wait is counted from the moment the
   * entry was written. So a repeated attempt reports the whole wait of the operation, not
   * the distance since the last try.
   *
   * @param call The call to dispatch
   * @param previouslyAttempted Whether the entry was dispatched before
   * @param writtenAt When the entry was written, or <code>null</code> where the
   *          document carries no such moment
   */
  private void dispatchMeasuringTheWait(
      final PhaseTwoCall call,
      final boolean previouslyAttempted,
      final Instant writtenAt) {

    try {
      phaseTwoRouter
          .getObject()
          .dispatch(call, previouslyAttempted);
    } catch (final RuntimeException e) {
      reportWait(writtenAt, DispatchOutcome.FAILED);
      throw e;
    }
    reportWait(writtenAt, DispatchOutcome.SUCCEEDED);

  }

  /**
   * @param writtenAt When the entry was written. <code>null</code> only for a document
   *          which somebody else wrote into the collection without that field. Its attempt
   *          is not measured, rather than measured from now
   * @param outcome How the attempt ended
   */
  private void reportWait(
      final Instant writtenAt,
      final DispatchOutcome outcome) {

    if (writtenAt == null) {
      return;
    }
    SpringBootMigrationAdapterAutoConfiguration
        .vanillaBpMetricsOf(metrics)
        .outboxDispatchEnded(
            MongoPhaseTwoOutbox.class.getSimpleName(),
            outcome,
            PhaseTwoOutbox
                .waitedSince(writtenAt)
                .toNanos());

  }

  /**
   * Counts an entry which this store gave up on. The gauge of waiting entries drops at the
   * same moment. So without this counter, the only number an operator watches would move as
   * if things had got better.
   *
   * @param operation The persisted name of the operation which was lost
   * @param permanent Whether the adapter said that repeating cannot help
   */
  private void countBlockedEntry(
      final String operation,
      final boolean permanent) {

    SpringBootMigrationAdapterAutoConfiguration
        .vanillaBpMetricsOf(metrics)
        .outboxEntryBlocked(MongoPhaseTwoOutbox.class.getSimpleName(), operation, permanent);

  }

  /**
   * Says when an entry whose attempt did not get through is to be read again. It counts
   * that attempt and gives the lease back. All three happen in one write. An entry which is
   * due again while it is still leased would wait until the end of the lease. The reason
   * goes into the same write. So an entry which is still being repeated says why it hangs.
   *
   * @param nextAttempt When it is to be read again
   * @param reason Why the attempt did not get through, as {@link LastFailure} words it
   * @return The update to apply
   */
  private static Update dueAgainAt(
      final Instant nextAttempt,
      final String reason) {

    return new Update()
        .set("nextAttemptAt", nextAttempt)
        .inc("attempts", 1)
        .unset("leasedBy")
        .unset("leasedUntil")
        .set("lastFailure", reason);

  }

  /**
   * Says when the entry is to be read again after its BPMS did not report the workflow
   * yet, and gives the lease back. It is {@link #dueAgainAt(Instant, String)} without the
   * count. Waiting for a read model is not a failed attempt. Counting it blocked entries
   * after a few minutes of a stopped exporter. The time the entry waited is read from
   * <code>createdAt</code> instead. The answer is written as the last failure, because it
   * is why the entry waits.
   *
   * @param nextAttempt When the entry is to be read again
   * @param reason The answer of the adapter, as {@link LastFailure} words it
   * @return The update to apply
   */
  private static Update dueAgainWithoutCountingAt(
      final Instant nextAttempt,
      final String reason) {

    return new Update()
        .set("nextAttemptAt", nextAttempt)
        .unset("leasedBy")
        .unset("leasedUntil")
        .set("lastFailure", reason);

  }

  /**
   * Blocks an entry and releases its <code>dedupKey</code>, the same way a dispatched entry
   * releases it. This is easy to miss, and it is why a blocked entry used to be a dead end.
   * The key refuses a second schedule of the same operation. So a blocked entry which kept
   * it would silence the very repetition the application needs. The application would ask,
   * the outbox would answer no, and that answer looks exactly like a correct
   * deduplication. The document stays for whoever repairs it, and the new attempt of the
   * operation is a document of its own. The reason goes into the same write. So whoever
   * finds the document reads why, without the log.
   *
   * @param entryId The id of the entry to block
   * @param reason Why it is blocked, as {@link LastFailure} words it
   * @return The update to apply
   */
  private static Update blockEntry(
      final String entryId,
      final String reason) {

    return new Update()
        .set("status", PhaseTwoOutboxEntry.STATUS_BLOCKED)
        .set("dedupKey", entryId)
        .set("lastFailure", reason)
        // the attempt which led here is counted, and the lease is given back
        .inc("attempts", 1)
        .unset("leasedBy")
        .unset("leasedUntil");

  }

  @Override
  public String storeName() {

    return getClass().getSimpleName();

  }

  @Override
  public boolean claimHousekeepingUntil(
      final String owner,
      final Instant until) {

    return housekeepingLease.claimUntil(collection, owner, until);

  }

  @Override
  public void releaseHousekeeping(
      final String owner) {

    housekeepingLease.release(collection, owner);

  }

  /**
   * {@inheritDoc}
   * <p>
   * MongoDB knows no limit on a delete. So the ids of the entries which may go are read
   * first, at most as many as asked for. They go into one <code>deleteMany</code>. The
   * index over the status and the moment an entry was dispatched serves the read.
   */
  @Override
  public int removeDispatchedEntriesOlderThan(
      final Instant threshold,
      final int maxRows) {

    if (maxRows < 1) {
      return 0;
    }
    final var expired = Query
        .query(Criteria
            .where("status")
            .is(PhaseTwoOutboxEntry.STATUS_DONE)
            .and("doneAt")
            .lt(threshold))
        .limit(maxRows);
    expired.fields().include("_id");
    final var ids = mongoTemplate
        .find(expired, PhaseTwoOutboxEntry.class, collection)
        .stream()
        .map(PhaseTwoOutboxEntry::getId)
        .toList();
    if (ids.isEmpty()) {
      return 0;
    }
    return (int) mongoTemplate
        .remove(Query.query(Criteria.where("_id").in(ids)), collection)
        .getDeletedCount();

  }

  /**
   * {@inheritDoc}
   * <p>
   * The entries were removed first. So nothing names their payloads now, and they go with
   * this call. A payload which an entry still names is not removed by age at all. Such an
   * entry waits, or it is blocked until somebody repairs it. The payload store asks its own
   * entries about that.
   */
  @Override
  public int removeOrphanedPayloadsOlderThan(
      final Instant threshold,
      final int maxRows) {

    return payloadStore.removeOrphansOlderThan(threshold, maxRows);

  }

  @Override
  public OptionalLong countDispatchedEntriesOlderThan(
      final Instant threshold) {

    try {
      return OptionalLong
          .of(mongoTemplate
              .count(
                  Query.query(Criteria
                      .where("status")
                      .is(PhaseTwoOutboxEntry.STATUS_DONE)
                      .and("doneAt")
                      .lt(threshold)),
                  collection));
    } catch (final RuntimeException e) {
      // a number which could not be read stays a gap in the meter, not a zero
      log.debug("Could not count the dispatched entries of the outbox collection '{}'", collection, e);
      return OptionalLong.empty();
    }

  }

  /**
   * Where the bytes of a call which carries a payload lie. The store which writes an entry
   * writes them there, in the same transaction.
   *
   * @return The payload store of this outbox
   */
  MongoPhaseTwoPayloadStore getPayloadStore() {

    return payloadStore;

  }

}
