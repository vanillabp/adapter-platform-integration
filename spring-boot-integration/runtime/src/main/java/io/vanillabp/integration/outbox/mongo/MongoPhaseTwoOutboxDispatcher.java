package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;
import java.util.Collection;
import java.util.Set;

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
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.DispatchOutcome;
import io.vanillabp.integration.adapter.migration.outbox.DispatchLease;
import io.vanillabp.integration.adapter.migration.outbox.DueEntryPoller;
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
 * renewing and another one takes the entry over once the lease ran out. A dispatch which
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
public class MongoPhaseTwoOutboxDispatcher {

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
   * What a running dispatch holds its entry with, and what keeps that hold alive while it
   * runs.
   */
  private final DispatchLease lease;

  /**
   * Where the bytes of a call which carries a payload lie while its entry waits.
   */
  private final MongoPhaseTwoPayloadStore payloadStore;

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
        mongoTemplate, properties.getMongo().payloadCollectionName());
    this.poller = new DueEntryPoller(
        "vanillabp-outbox", properties.getPollInterval(), this::poll, this::earliestDueAt);
    this.lease = new DispatchLease("vanillabp-outbox-lease", properties.getAttemptFrequency());

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

  }

  /**
   * Stops the poller and the renewal of the leases. What a dispatch was still carrying keeps
   * its lease until it runs out, so another node takes the entry then.
   */
  @PreDestroy
  public void stopPolling() {

    poller.stop();
    lease.stop();

  }

  /**
   * Pulls the next poll forward to now (used right after a commit, where the entry just
   * written wants to go out at once).
   */
  public void triggerPoll() {

    poller.somethingIsDueAt(Instant.now());

  }

  /**
   * Claims and dispatches all due entries, then deletes DONE entries whose retention
   * passed. Exceptions are caught to keep the poller alive.
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
        dispatch(entry);
      }
      cleanupDoneEntries();
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
   * Runs the attempt with the lease of its entry being renewed, and lets the renewal go the
   * moment the attempt is over.
   * <p>
   * The renewal covers the attempt and not the write which says how it ended. That write needs
   * a lease which has not run out, which the last renewal gave it, and not one which is still
   * growing - and a renewal outliving the mark would find the entry no longer OPEN and report a
   * lease it never lost.
   *
   * @param entryId The entry being dispatched
   * @param call The call to dispatch
   * @param previouslyAttempted Whether a dispatch has had this entry before
   * @param writtenAt When the entry was written
   */
  private void dispatchRenewingTheLease(
      final String entryId,
      final PhaseTwoCall call,
      final boolean previouslyAttempted,
      final Instant writtenAt) {

    try (var held = lease.renewWhile(entryId, this::renewLease)) {
      dispatchMeasuringTheWait(call, previouslyAttempted, writtenAt);
    }

  }

  /**
   * Dispatches a single claimed entry through the core's {@link PhaseTwoRouter}. On
   * success the entry is marked DONE; on failure it stays claimed and is retried
   * after the configured backoff, until it is blocked.
   *
   * @param entry The claimed entry (holding the state before it was claimed)
   */
  private void dispatch(
      final PhaseTwoOutboxEntry entry) {

    try {
      // an entry which was taken before is one whose dispatch may have reached the BPMS
      // already (recovered/retried): the router then runs
      // the START re-dispatch mitigation. The operation travels as its persisted
      // name and is resolved by the router's operation registry
      // the one extra read this form costs, and only for an entry which names a
      // payload: a lookup by _id, once per dispatch attempt
      final var payloadReference = entry.getArgs() == null
          ? null
          : entry.getArgs().get(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
      final var payload = payloadReference == null
          ? null
          : payloadStore.read(payloadReference);
      dispatchRenewingTheLease(
          entry.getId(),
          PhaseTwoCall
              .forDispatch(
                  entry.getOperation(), entry.getWorkflowModuleId(), entry.getBpmnProcessId(), entry
                      .getAggregateId(),
                  entry.getAdapterId(), entry.getArgs(), payload),
          wasTakenBefore(entry),
          entry.getCreatedAt());
      mongoTemplate.updateFirst(
          Query.query(Criteria.where("_id").is(entry.getId())),
          new Update()
              .set("status", PhaseTwoOutboxEntry.STATUS_DONE)
              .set("doneAt", Instant.now())
              // the deduplication window ends with the dispatch: the entry's own id
              // takes the place of the key, which stays readable in idempotencyKey
              .set("dedupKey", entry.getId())
              // the attempt is counted where it ended, and the lease given back
              .inc("attempts", 1)
              .unset("leasedBy")
              .unset("leasedUntil"),
          collection);
      // the entry is dispatched, so its bytes have done their work. Removed AFTER the
      // entry was marked, never before: a crash in between leaves a document the
      // housekeeping deletes, while the other order would leave an entry whose payload
      // is gone
      if (payloadReference != null) {
        payloadStore.remove(payloadReference);
      }
    } catch (Exception e) {
      // the adapter said that repeating cannot help - blocked right away
      // instead of after the configured attempts
      if (io.vanillabp.integration.spi.PhaseTwoPermanentFailure.isPermanent(e)) {
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(entry.getId())),
            blockEntry(entry.getId()),
            collection);
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
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(entry.getId())),
            blockEntry(entry.getId()),
            collection);
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
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(entry.getId())),
            dueAgainAt(Instant.now().plus(retryAfter)),
            collection);
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
      } else {
        // the entry holds the attempts which ended before this one, so attemptDelay(0) is
        // the distance after the first failure: close, because most failures are
        // momentary
        final var retryIn = properties.attemptDelay(entry.getAttempts());
        mongoTemplate.updateFirst(
            Query.query(Criteria.where("_id").is(entry.getId())),
            dueAgainAt(Instant.now().plus(retryIn)),
            collection);
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
    }

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

  /**
   * Deletes successfully dispatched (DONE) entries whose retention period passed - the
   * asynchronous cleanup of the "DONE instead of delete" contract - and then the
   * payloads which belong to no entry any more.
   * <p>
   * The order is what makes the retention count at the entry: the payload of an entry
   * deleted a moment ago is named by nothing now, so it goes with it, while the payload
   * of an entry which waits or is blocked is named and stays, however long the repair
   * takes.
   */
  private void cleanupDoneEntries() {

    final var expiredBefore = Instant.now().minus(properties.getRetention());
    mongoTemplate.remove(
        Query.query(Criteria
            .where("status")
            .is(PhaseTwoOutboxEntry.STATUS_DONE)
            .and("doneAt")
            .lt(expiredBefore)),
        collection);
    // the payloads of the entries just deleted, and what a rollback without a MongoDB
    // transaction left behind. What an entry still names is not removed by age at all
    payloadStore.removeOrphansOlderThan(expiredBefore, this::referencesStillNamed);

  }

  /**
   * Which of the given payloads an entry of this collection still names, asked with one
   * query. The reference lies in the entry's <code>args</code>, which no index spans,
   * so the query reads the collection - and it is asked only where a payload outlived
   * the retention, which on a healthy store is never.
   *
   * @param references The payloads the housekeeping is about to remove
   * @return Those of them an entry names
   */
  private Set<String> referencesStillNamed(
      final Collection<String> references) {

    final var named = "args.%s".formatted(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
    try {
      return Set.copyOf(
          mongoTemplate.findDistinct(
              Query.query(Criteria.where(named).in(references)), named, collection, String.class));
    } catch (final RuntimeException e) {
      // nothing is removed then: a payload kept too long costs space, a payload removed
      // from an entry which still waits costs the dispatch
      log.warn("Could not ask the outbox collection '{}' which payloads it still names", collection, e);
      return Set.copyOf(references);
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
