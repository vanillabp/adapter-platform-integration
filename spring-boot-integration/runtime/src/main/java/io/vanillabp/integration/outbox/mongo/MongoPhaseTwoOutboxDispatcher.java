package io.vanillabp.integration.outbox.mongo;

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
import io.vanillabp.integration.adapter.migration.outbox.DispatchLanes;
import io.vanillabp.integration.adapter.migration.outbox.DispatchLease;
import io.vanillabp.integration.adapter.migration.outbox.DueEntryPoller;
import io.vanillabp.integration.adapter.migration.outbox.OutboxHousekeeping;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseTwoCall;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches committed-but-unprocessed entries of the MongoDB-based phase-two outbox
 * through the core's {@link PhaseTwoRouter}:
 * <ul>
 * <li>right after a commit (triggered by {@link MongoPhaseTwoOutbox}) and</li>
 * <li>by a poller (crash recovery and retries) started on
 * {@link ApplicationReadyEvent}, which sleeps until the earliest entry it still owes
 * something to is due rather than polling on a rhythm - bounded by
 * <code>vanillabp.outbox.poll-interval</code> for work another node wrote down before it
 * died (see {@link DueEntryPoller}).</li>
 * </ul>
 * Due entries (status {@link PhaseTwoOutboxEntry#STATUS_OPEN}) are claimed atomically
 * (find-and-modify writing <code>leasedBy</code> and <code>leasedUntil</code>, the lease
 * lasting one <code>vanillabp.outbox.attempt-frequency</code>), so multiple instances do not
 * dispatch the same entry concurrently. <strong>A running dispatch renews its lease</strong>
 * ({@link DispatchLease}), so an entry travelling longer than that distance stays the claim
 * of the node carrying it instead of being taken by the next poll; a node which dies stops
 * renewing and another one takes the entry over once the lease ran out. Every write which
 * says how an attempt ended names the holder of the lease, so a node which lost its entry
 * writes nothing over what the node holding it wrote. A dispatch which
 * FAILS writes the next attempt itself, at the growing distance of
 * {@link io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties#attemptDelay(int)}
 * - doubling per attempt up to <code>vanillabp.outbox.max-attempt-frequency</code>. On successful dispatch the entry is marked
 * {@link PhaseTwoOutboxEntry#STATUS_DONE} - it stays in the collection for support to
 * read, its <code>dedupKey</code> is replaced by its own id so a repetition of the same
 * operation can be planned again, and it is deleted asynchronously once
 * <code>vanillabp.outbox.retention</code> passed. After
 * <code>vanillabp.outbox.block-after-attempts</code> failed attempts an entry is
 * marked {@link PhaseTwoOutboxEntry#STATUS_BLOCKED} and has to be cleaned up
 * manually.
 * <p>
 * <strong><code>attempts</code> counts attempts, not claims.</strong> The field is written
 * when an attempt ENDED, together with what became of the entry, so a dispatch which takes
 * its time uses up no attempt budget.
 * <p>
 * The poller runs on a private single-thread daemon executor - no
 * {@link org.springframework.scheduling.TaskScheduler} bean is registered or used, so
 * an application's own scheduling setup (e.g. <code>&#64;EnableScheduling</code>)
 * stays unaffected.
 */
@Slf4j
public class MongoPhaseTwoOutboxDispatcher implements OutboxHousekeeping.Store {

  private final MongoTemplate mongoTemplate;

  private final ObjectProvider<PhaseTwoRouter> phaseTwoRouter;

  private final PhaseTwoOutboxProperties properties;

  /**
   * The collection polled for outbox entries - the same one its
   * {@link MongoPhaseTwoOutbox} writes to.
   */
  private final String collection;

  /**
   * What a blocked entry is counted into. A provider and not the bean itself, because
   * Micrometer is optional and the application may bring no metrics at all.
   */
  private final ObjectProvider<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> metrics;

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
   * Where this node says that it is house-keeping this store tonight, so no other node
   * measures its work at the same time.
   */
  private final MongoHousekeepingLease housekeepingLease;

  /**
   * What removes the dispatched entries and the orphaned payloads, and when.
   */
  private final OutboxHousekeeping housekeeping;

  /**
   * Builds the dispatcher together with its payload store, its lease and its poller. The
   * poller does not run yet: it is started once workflow processing did, so nothing is
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
      final ObjectProvider<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> metrics) {

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
   * When this store owes something: the due time of the earliest entry waiting for its
   * dispatch, or the moment the oldest dispatched entry may be deleted, whichever comes
   * first. A BLOCKED entry is in neither set - it waits for a person rather than for a
   * clock, so it must not keep the poller awake.
   * <p>
   * An entry being dispatched right now answers with the end of its lease and not with
   * "due": the claim and every renewal push <code>nextAttemptAt</code> along with
   * <code>leasedUntil</code>. Without that the poller would be told "due now" for as long as
   * the dispatch runs and would ask again at its shortest sleep.
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
   * The smallest value of one field among the documents a query matches, read as one
   * document rather than as an aggregation, so the sort is served by the index this store creates
   * over the status and that field.
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
   * Starts the poller. The first run is executed immediately, dispatching
   * committed-but-unprocessed entries of a previously crashed instance. The listener
   * order guarantees that workflow processing started BEFORE any recovered entry is
   * dispatched (see
   * {@link io.vanillabp.integration.deployment.SpringBootDeploymentService#OUTBOX_DISPATCHER_LISTENER_ORDER}).
   */
  @Order(io.vanillabp.integration.deployment.SpringBootDeploymentService.OUTBOX_DISPATCHER_LISTENER_ORDER)
  @EventListener(ApplicationReadyEvent.class)
  public void startPolling() {

    poller.start();
    housekeeping.start();

  }

  /**
   * Stops the poller, the lanes and the renewal of the leases. What a lane was still holding
   * stays OPEN in the collection and keeps its lease until it runs out, so another node takes
   * the entry then.
   */
  @PreDestroy
  public void stopPolling() {

    poller.stop();
    lanes.stop();
    lease.stop();
    housekeeping.stop();

  }

  /**
   * Pulls the next poll forward to now (used right after a commit, where the entry just
   * written wants to go out at once).
   */
  public void triggerPoll() {

    poller.somethingIsDueAt(Instant.now());

  }

  /**
   * Claims all due entries and hands each of them to the lane of its aggregate, then deletes
   * DONE entries whose retention passed. Exceptions are caught to keep the poller alive.
   * <p>
   * The claim is what this thread does and the dispatch is what a lane does, and the order
   * matters: an entry is claimed before it is handed over, so the claim of the next poll -
   * here or on another node - finds it leased and leaves it alone.
   * <p>
   * A lane whose queue is full makes this thread wait. Waiting is the back pressure of a
   * backlog which arrives faster than it leaves, and it is what keeps the backlog in the
   * collection, where it can be read.
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
        // the oldest entry first, which is what the lanes need: they keep the order they are
        // handed the entries of one aggregate in, and a claim in whatever order the collection
        // happens to answer in would hand them over the wrong way round. Served by the index
        // over the status and that moment
        due.with(Sort.by(Sort.Direction.ASC, "createdAt"));
        // claim the entry atomically: this node's name and the end of the lease, so other
        // instances skip it while the dispatch runs and renews. A free lease is what makes
        // an entry available, never the number of attempts, which says what ENDED
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
   * Pushes the lease of an entry this node is dispatching along, in a write of its own, once
   * per tick of {@link DispatchLease}.
   *
   * @param entryId The entry being dispatched
   * @param leaseEnd How long the lease is to last now
   * @return Whether this node still holds the entry - <code>false</code> where the write
   *         matched no document, which is the entry being gone or held by somebody else
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
      // a collection which cannot be asked says nothing about who holds the entry, so the
      // renewal keeps trying rather than giving it up over one hiccup
      log.warn("Could not renew the lease of the phase-two outbox entry '{}' - trying again", entryId, e);
      return true;
    }

  }

  /**
   * Hands the entry the poll has just claimed to the lane of its aggregate, with the renewal
   * of its lease already running.
   * <p>
   * The renewal starts here and not where the lane picks the entry up, because between the two
   * the entry waits in the lane's queue. A wait longer than the lease would let another node
   * claim an entry this one is about to dispatch, and both would carry the operation out.
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
   * What an entry is ordered by: its workflow aggregate. An entry which names none - a
   * broadcast signal does not - is ordered by its BPMN process instead, so two signals of one
   * process still leave in the order they were planned.
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
   * The renewal ends here, before the write which says how the attempt ended. That write needs
   * a lease which has not run out, which the last renewal gave it, and not one which is still
   * growing - and a renewal outliving the write would find the entry no longer OPEN and report
   * a lease it never lost.
   *
   * @param entry The claimed entry (holding the state before it was claimed)
   * @param held The renewal which started with the claim, closed when this attempt is over
   */
  private void dispatch(
      final PhaseTwoOutboxEntry entry,
      final DispatchLease.Held held) {

    final String payloadReference;
    // everything the attempt does is inside, so the renewal is let go whichever way the
    // attempt ends - reading the arguments of the entry included
    try (held) {
      // an entry which was taken before is one whose dispatch may have reached the BPMS
      // already (recovered/retried): the router then runs
      // the START re-dispatch mitigation. The operation travels as its persisted
      // name and is resolved by the router's operation registry
      // the one extra read this form costs, and only for an entry which names a
      // payload: a lookup by _id, once per dispatch attempt
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
        // the deduplication window ends with the dispatch: the entry's own id
        // takes the place of the key, which stays readable in idempotencyKey
        .set("dedupKey", entry.getId())
        // the attempt is counted where it ended, and the lease given back
        .inc("attempts", 1)
        .unset("leasedBy")
        .unset("leasedUntil");
    try {
      if (!writeAsTheHolder(entry, markedDone)) {
        // the entry belongs to another node, and so do its bytes: that node may still be
        // dispatching and would find a payload which is gone
        return;
      }
    } catch (final RuntimeException e) {
      // the dispatch got through and the mark did not, so the entry says nothing about what
      // happened. It is planned again, which repeats the operation - the at-least-once the
      // contract names. Said out loud through the same report a failed dispatch uses,
      // because a mark lost in silence leaves an operation looking undone with nobody to ask
      reportFailedDispatch(entry, e);
      return;
    }
    // the entry is dispatched, so its bytes have done their work. Removed AFTER the
    // entry was marked, never before: a crash in between leaves a document the
    // housekeeping deletes, while the other order would leave an entry whose payload
    // is gone
    if (payloadReference != null) {
      payloadStore.remove(payloadReference);
    }

  }

  /**
   * Writes down what a failed dispatch means for the entry, and keeps a write which cannot
   * get through inside this lane.
   * <p>
   * The mark path is guarded the same way: what the store throws there ends as a report
   * rather than as an exception leaving the runnable of a lane, where it would be printed
   * as "Exception in thread vanillabp-outbox-dispatch-1" with a stack trace nobody can
   * place. The failure path used to be the one which was not guarded, and a shutdown
   * during a dispatch is exactly when it is not.
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
   * Says that how an attempt ended was not written down, and says which of the two cases it
   * is.
   * <p>
   * Nothing is lost either way: the entry stays as it was, keeps its lease, and the next
   * node to pick it up dispatches it again once that lease runs out - the at-least-once the
   * contract names. What differs is who has to do something. A node being STOPPED is the
   * ordinary case of {@code JdbcPhaseTwoOutboxDispatcher#reportTheEntryNoLaneTook} and is
   * said at INFO, because a stack trace there sends an operator looking for a fault where a
   * deployment was. Everything else keeps the sharpness it had.
   * <p>
   * The interrupt flag is set again before the line is written: the work of this lane is
   * over, and whoever asks the thread next has to see that it was interrupted.
   *
   * @param entry The entry this attempt ran on
   * @param e What the store threw while the result was being written
   */
  private void reportAResultWhichWasNotWritten(
      final PhaseTwoOutboxEntry entry,
      final RuntimeException e) {

    if (io.vanillabp.integration.adapter.migration.outbox.AStoppingNode.isTheReasonFor(e)) {
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
   * Writes down what a failed dispatch means for the entry: blocked where repeating cannot
   * help or where the attempts are used up, and a new due time otherwise.
   *
   * @param entry The entry whose dispatch failed
   * @param e What the dispatch threw
   */
  private void writeDownTheFailedDispatch(
      final PhaseTwoOutboxEntry entry,
      final Exception e) {

    // the adapter said that repeating cannot help - blocked right away
    // instead of after the configured attempts
    if (io.vanillabp.integration.spi.PhaseTwoPermanentFailure.isPermanent(e)) {
      if (!writeAsTheHolder(entry, blockEntry(entry.getId()))) {
        return;
      }
      countBlockedEntry(entry.getOperation(), true);
      log.error(
          "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "failed for a reason repeating cannot fix - the outbox entry '{}' is blocked and has "
              + "to be cleaned up manually!",
          entry.getOperation(),
          entry.getBpmnProcessId(),
          entry.getWorkflowModuleId(),
          entry.getAggregateId(),
          entry.getId(),
          e);
      return;
    }
    if (entry.getAttempts() + 1 >= properties.getBlockAfterAttempts()) {
      if (!writeAsTheHolder(entry, blockEntry(entry.getId()))) {
        return;
      }
      countBlockedEntry(entry.getOperation(), false);
      log.error(
          "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "failed {} times - the outbox entry '{}' is now blocked and has to be cleaned up manually!",
          entry.getOperation(),
          entry.getBpmnProcessId(),
          entry.getWorkflowModuleId(),
          entry.getAggregateId(),
          entry.getAttempts() + 1,
          entry.getId(),
          e);
      return;
    }
    final var retryAfter = io.vanillabp.integration.spi.PhaseTwoRetryLater.retryAfter(e);
    if (retryAfter != null) {
      // the dispatch knows when asking again can help - a workflow the BPMS has not
      // made searchable yet is the case - so the entry waits that long instead of the
      // configured backoff. What ends a reason which never goes away is the attempts
      // counted above, not this due time
      if (!writeAsTheHolder(entry, dueAgainAt(Instant.now().plus(retryAfter)))) {
        return;
      }
      log.info(
          "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' cannot "
              + "run yet - the outbox entry '{}' is dispatched again in {} ({} of {} attempts used): {}",
          entry.getOperation(),
          entry.getBpmnProcessId(),
          entry.getWorkflowModuleId(),
          entry.getAggregateId(),
          entry.getId(),
          retryAfter,
          entry.getAttempts() + 1,
          properties.getBlockAfterAttempts(),
          e.getMessage());
      return;
    }
    // the entry holds the attempts which ended before this one, so attemptDelay(0) is
    // the distance after the first failure: close, because most failures are
    // momentary
    final var retryIn = properties.attemptDelay(entry.getAttempts());
    if (!writeAsTheHolder(entry, dueAgainAt(Instant.now().plus(retryIn)))) {
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
   * Writes down how an attempt ended, on the entry this node holds. The name of this node is
   * in the condition next to the id, so a node whose lease was taken over writes nothing: the
   * node holding the entry is carrying the same operation out, and its answer is the one the
   * collection keeps. The most expensive write to lose that race is a block over an entry the
   * other node has just dispatched, because somebody would be asked to repair an operation
   * which succeeded.
   *
   * @param entry The entry this attempt ran on
   * @param update What to write on it
   * @return Whether this node still held the entry, so the write took
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
   * Says that an attempt ended on an entry this node does not hold any more, so what it wanted
   * to write was dropped.
   * <p>
   * The renewal said the same thing earlier, at the moment the entry changed hands. This
   * message is the other end of it and is worth its own line: it names the operation which ran
   * twice, and it is the proof that the second run did not overwrite what the node holding the
   * entry wrote.
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
   * Whether a dispatch has had this entry before, which is what makes a START probe the BPMS
   * it was meant for instead of starting a second workflow. An attempt which ended is
   * counted; an attempt whose node died in the middle left its name on the entry and nothing
   * else, and the claim answers with the document as it was BEFORE the claim, so that name is
   * the one of the node before this one.
   *
   * @param entry The claimed entry, as it stood before the claim
   * @return Whether an attempt ended or a holder disappeared
   */
  private static boolean wasTakenBefore(
      final PhaseTwoOutboxEntry entry) {

    return (entry.getAttempts() > 0) || (entry.getLeasedBy() != null);

  }

  /**
   * Hands the call to the core's router and reports how long its entry waited for this
   * attempt, whether the attempt succeeded or threw. The wait is counted from the
   * moment the entry was written, so a repeated attempt reports the whole wait of the
   * operation and not the distance since the last try.
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
   * @param writtenAt When the entry was written - <code>null</code> only for a document
   *          somebody else wrote into the collection without that field, whose attempt
   *          is not measured rather than measured from now
   * @param outcome How the attempt ended
   */
  private void reportWait(
      final Instant writtenAt,
      final DispatchOutcome outcome) {

    if (writtenAt == null) {
      return;
    }
    io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration
        .vanillaBpMetricsOf(metrics)
        .outboxDispatchEnded(
            MongoPhaseTwoOutbox.class.getSimpleName(),
            outcome,
            io.vanillabp.integration.spi.PhaseTwoOutbox
                .waitedSince(writtenAt)
                .toNanos());

  }

  /**
   * Counts an entry this store gave up on. The gauge of waiting entries drops at the
   * same moment, so without this counter the only number an operator watches would move
   * as if things had got better.
   *
   * @param operation The persisted name of the operation which was lost
   * @param permanent Whether the adapter said that repeating cannot help
   */
  private void countBlockedEntry(
      final String operation,
      final boolean permanent) {

    io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration
        .vanillaBpMetricsOf(metrics)
        .outboxEntryBlocked(MongoPhaseTwoOutbox.class.getSimpleName(), operation, permanent);

  }

  /**
   * Says when an entry whose attempt did not get through is to be read again, counts that
   * attempt and gives the lease back. All three in one write, because an entry which is due
   * again while still leased would be waited out to the end of the lease.
   *
   * @param nextAttempt When it is to be read again
   * @return The update to apply
   */
  private static Update dueAgainAt(
      final Instant nextAttempt) {

    return new Update()
        .set("nextAttemptAt", nextAttempt)
        .inc("attempts", 1)
        .unset("leasedBy")
        .unset("leasedUntil");

  }

  /**
   * Blocks an entry and releases its <code>dedupKey</code> the way a dispatched entry
   * releases it. Easy to miss and the reason a blocked entry used to be a dead end: the
   * key is what refuses a second schedule of the same operation, so a blocked entry
   * which kept it would silence the very repetition the application needs - it would
   * ask, the outbox would answer no, and that answer looks exactly like a correct
   * deduplication. The document stays for whoever repairs it, and the new attempt of the
   * operation is a document of its own.
   *
   * @param entryId The id of the entry to block
   * @return The update to apply
   */
  private static Update blockEntry(
      final String entryId) {

    return new Update()
        .set("status", PhaseTwoOutboxEntry.STATUS_BLOCKED)
        .set("dedupKey", entryId)
        // the attempt which led here is counted, and the lease given back
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
   * MongoDB knows no bound on a delete, so the ids of the entries which may go are read
   * first, at most as many as asked for, and they go into one <code>deleteMany</code>.
   * The read is served by the index over the status and the moment an entry was
   * dispatched.
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
   * The entries were removed first, so the payloads they named are named by nothing now
   * and go with this call. What an entry still names - an entry which waits, and an entry
   * which is blocked until somebody repairs it - is not removed by age at all, which the
   * payload store asks its own entries about.
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
      // a number which could not be read stays a gap in the meter rather than a zero
      log.debug("Could not count the dispatched entries of the outbox collection '{}'", collection, e);
      return OptionalLong.empty();
    }

  }

  /**
   * Where the bytes of a call which carries a payload lie - what the store writing an
   * entry writes them into, in the same transaction.
   *
   * @return The payload store of this outbox
   */
  MongoPhaseTwoPayloadStore getPayloadStore() {

    return payloadStore;

  }

}
