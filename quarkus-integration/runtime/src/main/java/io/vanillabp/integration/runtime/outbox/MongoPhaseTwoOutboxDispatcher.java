package io.vanillabp.integration.runtime.outbox;

import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bson.Document;
import org.eclipse.microprofile.config.ConfigProvider;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.mongo.MongoSchema;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.DispatchOutcome;
import io.vanillabp.integration.adapter.migration.outbox.DispatchLanes;
import io.vanillabp.integration.adapter.migration.outbox.DispatchLease;
import io.vanillabp.integration.adapter.migration.outbox.DueEntryPoller;
import io.vanillabp.integration.adapter.migration.outbox.Housekeeping;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.runtime.deployment.VanillaBpDeploymentRunner;
import io.vanillabp.integration.runtime.mongo.MongoIndexes;
import io.vanillabp.integration.spi.PhaseTwoCall;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches committed-but-unprocessed entries of the MongoDB-based phase-two
 * outbox (see {@link MongoPhaseTwoOutbox}) through the core's
 * {@link PhaseTwoRouter}:
 * <ul>
 * <li>right after a commit (triggered by {@link MongoPhaseTwoOutbox}) and</li>
 * <li>by a poller (crash recovery and retries) started on {@link StartupEvent}, which
 * sleeps until the earliest entry this store still owes something to is due rather than
 * polling on a rhythm - bounded by <code>vanillabp.outbox.poll-interval</code> for work
 * another node wrote down before it died (see {@link DueEntryPoller}).</li>
 * </ul>
 * Due entries (status {@link MongoPhaseTwoOutbox#STATUS_OPEN}) are claimed
 * atomically (<code>findOneAndUpdate</code> writing <code>leasedBy</code> and
 * <code>leasedUntil</code>, the lease lasting one
 * <code>vanillabp.outbox.attempt-frequency</code>), so
 * multiple application instances (pods) may poll concurrently without any distributed
 * lock - exactly one instance wins each claim. <strong>A running dispatch renews its
 * lease</strong> ({@link DispatchLease}), so an entry travelling longer than that distance
 * stays the claim of the node carrying it instead of being taken by the next poll; a node
 * which dies stops renewing and another one takes the entry over once the lease ran out.
 * Every write which says how an attempt ended names the holder of the lease, so a node which
 * lost its entry writes nothing over what the node holding it wrote.
 * <code>attempts</code> counts the attempts which ENDED, so a slow dispatch uses up no
 * attempt budget. A dispatch which FAILS writes the next
 * attempt itself, at the growing distance of
 * {@link io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties#attemptDelay(int)}
 * - doubling per attempt up to <code>vanillabp.outbox.max-attempt-frequency</code>. On
 * successful dispatch the entry is marked {@link MongoPhaseTwoOutbox#STATUS_DONE}
 * (kept until <code>vanillabp.outbox.retention</code> passed, for support to read);
 * after
 * <code>vanillabp.outbox.block-after-attempts</code> failed attempts it is marked
 * {@link MongoPhaseTwoOutbox#STATUS_BLOCKED} and has to be cleaned up manually.
 * <p>
 * Unless <code>vanillabp.outbox.create-schema</code> is disabled, the indexes of
 * {@link MongoSchema#OUTBOX_INDEXES} and {@link MongoSchema#PAYLOAD_INDEXES} are created
 * on startup. The unique one over <code>dedupKey</code> is what deduplicates: that field
 * carries the idempotency key only while the entry waits for its dispatch, so it
 * deduplicates the planned operations and not the ones which already reached the BPMS
 * (see {@link MongoPhaseTwoOutbox}); marking an entry DONE writes its id there. Where the
 * application manages its schema itself, the startup reads what the collections carry and
 * names every index which is missing, with the statement which creates it. The sparse
 * unique index earlier versions created over <code>idempotencyKey</code> is dropped where
 * it is still there - it would deduplicate dispatched entries as well.
 * <p>
 * The database is taken from <code>quarkus.mongodb.database</code> - the same
 * database the application's aggregates live in.
 */
@ApplicationScoped
@Slf4j
public class MongoPhaseTwoOutboxDispatcher {

  @Inject
  Instance<MongoClient> mongoClient;

  @Inject
  Instance<PhaseTwoRouter> phaseTwoRouter;

  /**
   * What a blocked entry is counted into. Unsatisfied where the application uses no
   * Micrometer extension, which is why it is resolved through the producer's helper
   * rather than injected directly.
   */
  @Inject
  Instance<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> vanillaBpMetrics;

  @Inject
  jakarta.transaction.TransactionSynchronizationRegistry txRegistry;

  private volatile PhaseTwoOutboxProperties properties;

  private volatile MongoPhaseTwoPayloadStore payloadStore;

  /**
   * Built by the CDI container, next to the outbox it dispatches for. The poller starts
   * on the startup event and not here, because a dispatch must not run before the BPMN
   * resources were deployed.
   */
  public MongoPhaseTwoOutboxDispatcher() {
  }

  /**
   * The outbox configuration (<code>vanillabp.outbox.*</code>), loaded lazily so
   * {@link MongoPhaseTwoOutbox} can resolve its collection even before the startup
   * event was observed.
   *
   * @return The outbox configuration
   */
  PhaseTwoOutboxProperties getProperties() {

    if (properties == null) {
      properties = QuarkusMigrationAdapterPropertiesMapper.INSTANCE.toCore(
          ConfigProvider
              .getConfig()
              .unwrap(SmallRyeConfig.class)
              .getConfigMapping(QuarkusMigrationAdapterProperties.class)
              .outbox());
    }
    return properties;

  }

  private volatile DueEntryPoller poller;

  /**
   * The threads the entries are dispatched on, and the rule which says that the entries of one
   * workflow aggregate share one of them. Built with the poller, because both need the
   * configuration.
   */
  private volatile DispatchLanes lanes;

  /**
   * What a running dispatch holds its entry with, and what keeps that hold alive while it
   * runs. Built with the poller, because both need the configuration.
   */
  private volatile DispatchLease lease;

  /**
   * Creates the unique index (unless disabled) and starts the fixed-delay poller.
   * The first run is executed immediately, dispatching committed-but-unprocessed
   * entries of a previously crashed instance. The observer priority guarantees that
   * the deployment pipeline deployed the BPMN resources and started workflow
   * processing BEFORE any recovered entry is dispatched (see
   * {@link VanillaBpDeploymentRunner#OUTBOX_DISPATCHER_STARTUP_PRIORITY}).
   *
   * @param event The startup event observed
   */
  void onStart(
      @Observes
      @Priority(VanillaBpDeploymentRunner.OUTBOX_DISPATCHER_STARTUP_PRIORITY) final StartupEvent event) {

    if (!mongoClient.isResolvable()) {
      log.debug("No MongoDB client available - the MongoDB-based phase-two outbox stays inactive");
      return;
    }

    getProperties();
    if (!properties.getMongo().isEnabled()) {
      log.debug("'vanillabp.outbox.mongo.enabled' is false - the MongoDB-based phase-two outbox stays inactive");
      return;
    }

    // what each of them is read by is described once, in the core, because the Spring Boot
    // integration creates the same ones - see decision 76 in the repository's DECISIONS.md
    // for the one over the payload references
    if (properties.isCreateSchema()) {
      MongoIndexes.createOn(outboxCollection(), MongoSchema.OUTBOX_INDEXES);
      MongoIndexes.createOn(payloadCollection(), MongoSchema.PAYLOAD_INDEXES);
      dropLegacyIdempotencyKeyIndex();
    } else {
      // the collections themselves need no check: MongoDB creates one with the first
      // document, so what an application managing its own schema owes are the indexes
      MongoIndexes.reportMissingOn(outboxCollection(), MongoSchema.OUTBOX_INDEXES);
      MongoIndexes.reportMissingOn(payloadCollection(), MongoSchema.PAYLOAD_INDEXES);
    }

    lease = new DispatchLease("vanillabp-outbox-lease", properties.getAttemptFrequency());
    lanes = new DispatchLanes("vanillabp-outbox-dispatch", properties.getDispatchThreads());
    poller = new DueEntryPoller(
        "vanillabp-outbox", properties.getPollInterval(), this::poll, this::earliestDueAt);
    poller.start();

  }

  /**
   * Stops the poller, the lanes and the renewal of the leases. What a lane was still holding
   * stays OPEN in the collection and keeps its lease until it runs out, so another node takes
   * the entry then.
   */
  @PreDestroy
  void shutdown() {

    if (poller != null) {
      poller.stop();
      poller = null;
    }
    if (lanes != null) {
      lanes.stop();
      lanes = null;
    }
    if (lease != null) {
      lease.stop();
      lease = null;
    }

  }

  /**
   * Removes the sparse unique index over <code>idempotencyKey</code> which earlier
   * versions created. It deduplicated dispatched entries as well, which is what this
   * store stopped doing; an index which is not there any more is not an error.
   */
  private void dropLegacyIdempotencyKeyIndex() {

    try {
      outboxCollection().dropIndex(Indexes.ascending("idempotencyKey"));
      log.info(
          "Dropped the outbox' unique index over 'idempotencyKey': deduplication now spans the "
              + "entries still waiting for their dispatch and uses 'dedupKey'");
    } catch (final RuntimeException e) {
      // 27 = IndexNotFound, which is the normal case
      log.debug("No legacy unique index over 'idempotencyKey' to drop", e);
    }

  }

  /**
   * Pulls the next poll forward to now (used right after a commit, where the entry just
   * written wants to go out at once).
   */
  public void triggerPoll() {

    final var running = poller;
    if (running != null) {
      running.somethingIsDueAt(Instant.now());
    }

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

    try {
      final var collection = outboxCollection();
      final var nextAttempt = earliest(
          collection,
          Filters.and(
              Filters.eq("status", MongoPhaseTwoOutbox.STATUS_OPEN),
              Filters.lt("attempts", properties.getBlockAfterAttempts())),
          "nextAttemptAt");
      final var oldestDone = earliest(
          collection,
          Filters.eq("status", MongoPhaseTwoOutbox.STATUS_DONE),
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
    } catch (final RuntimeException e) {
      // the poll which follows reports the same problem with its own message, and a poller
      // which stops asking is worse than one which asks at the configured cap
      log.debug("Could not read when the next phase-two outbox entry is due", e);
      return null;
    }

  }

  /**
   * The smallest value of one field among the documents a filter matches, read as one
   * document rather than as an aggregation, so the sort is served by the index this store creates
   * over the status and that field.
   *
   * @param collection The outbox collection
   * @param filter What to look at
   * @param field The field to order by and to read
   * @return The value or <code>null</code> where nothing matches
   */
  Instant earliest(
      final MongoCollection<Document> collection,
      final org.bson.conversions.Bson filter,
      final String field) {

    final var entry = collection
        .find(filter)
        .sort(Sorts.ascending(field))
        .projection(new Document(field, 1))
        .limit(1)
        .first();
    if (entry == null) {
      return null;
    }
    final var value = entry.getDate(field);
    return value == null ? null : value.toInstant();

  }

  /**
   * The collection storing the outbox entries, resolved from the database
   * configured by <code>quarkus.mongodb.database</code>.
   *
   * @return The outbox collection
   */
  MongoCollection<Document> outboxCollection() {

    return mongoClient
        .get()
        .getDatabase(databaseName())
        .getCollection(getProperties()
            .getMongo()
            .getCollection());

  }

  /**
   * The database the outbox and its payloads live in - the one the workflow aggregates
   * live in.
   *
   * @return The configured database name
   */
  private static String databaseName() {

    return ConfigProvider
        .getConfig()
        .getOptionalValue("quarkus.mongodb.database", String.class)
        .orElseThrow(() -> new IllegalStateException(
            """
                The MongoDB-based phase-two outbox needs the database name! Set the property \
                'quarkus.mongodb.database' (the same database the workflow aggregates live in)."""));

  }

  /**
   * The collection the payloads of the calls which carry one live in
   * (<code>vanillabp.outbox.mongo.payload-collection</code>), in the database
   * configured by <code>quarkus.mongodb.database</code>.
   *
   * @return The payload collection
   */
  MongoCollection<Document> payloadCollection() {

    return mongoClient
        .get()
        .getDatabase(databaseName())
        .getCollection(getProperties()
            .getMongo()
            .payloadCollectionName());

  }

  /**
   * Where the bytes of a call which carries a payload lie while its entry waits.
   *
   * @return The payload store of this outbox
   */
  MongoPhaseTwoPayloadStore getPayloadStore() {

    if (payloadStore == null) {
      payloadStore = new MongoPhaseTwoPayloadStore(
          this::payloadCollection, () -> getProperties().getMongo().getCollection(), txRegistry);
    }
    return payloadStore;

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
  private synchronized void poll() {

    try {
      final var collection = outboxCollection();
      while (true) {
        final var now = Instant.now();
        // claim atomically: this node's name and the end of the lease, so other instances
        // skip the entry while the dispatch runs and renews. A free lease is what makes an
        // entry available, never the number of attempts, which says what ENDED
        final var leaseEnds = Date.from(lease.endsAt());
        final var entry = collection.findOneAndUpdate(
            Filters.and(
                Filters.eq("status", MongoPhaseTwoOutbox.STATUS_OPEN),
                Filters.lte("nextAttemptAt", Date.from(now)),
                Filters.lt("attempts", properties.getBlockAfterAttempts()),
                Filters.or(
                    Filters.eq("leasedUntil", null),
                    Filters.lte("leasedUntil", Date.from(now)))),
            Updates.combine(
                Updates.set("leasedBy", lease.owner()),
                Updates.set("leasedUntil", leaseEnds),
                Updates.set("nextAttemptAt", leaseEnds)),
            // the oldest entry first, which is what the lanes need: they keep the order they
            // are handed the entries of one aggregate in, and a claim in whatever order the
            // collection happens to answer in would hand them over the wrong way round. Served
            // by the index over the status and that moment
            new FindOneAndUpdateOptions().sort(Sorts.ascending("createdAt")));
        if (entry == null) {
          break;
        }
        handOverToItsLane(collection, entry);
      }
      // asynchronous retention cleanup of the "DONE instead of delete" contract
      collection.deleteMany(
          Filters.and(
              Filters.eq("status", MongoPhaseTwoOutbox.STATUS_DONE),
              Filters.lt("doneAt", Date.from(Instant.now().minus(properties.getRetention())))));
      // the payloads of the entries just deleted, and what a rollback without a MongoDB
      // transaction left behind. What an entry still names is not removed by age at all,
      // so an entry which waits or is blocked keeps its bytes until it is dispatched - the
      // store joins the entries itself, in the database
      getPayloadStore()
          .removeOrphansOlderThan(
              Instant.now().minus(properties.getRetention()), Housekeeping.ROWS_PER_RUN);
    } catch (final RuntimeException e) {
      log.error("Polling the VanillaBP phase-two outbox failed - will retry", e);
    }

  }

  /**
   * Pushes the lease of an entry this node is dispatching along, in a write of its own, once
   * per tick of {@link DispatchLease}.
   *
   * @param collection The outbox collection
   * @param entryId The entry being dispatched
   * @param leaseEnd How long the lease is to last now
   * @return Whether this node still holds the entry - <code>false</code> where the write
   *         matched no document, which is the entry being gone or held by somebody else
   */
  private boolean renewLease(
      final MongoCollection<Document> collection,
      final String entryId,
      final Instant leaseEnd) {

    try {
      return collection
          .updateOne(
              Filters
                  .and(
                      Filters.eq("_id", entryId),
                      Filters.eq("leasedBy", lease.owner()),
                      Filters.eq("status", MongoPhaseTwoOutbox.STATUS_OPEN)),
              Updates
                  .combine(
                      Updates.set("leasedUntil", Date.from(leaseEnd)),
                      Updates.set("nextAttemptAt", Date.from(leaseEnd))))
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
   * @param collection The outbox collection
   * @param entry The entry this node has just claimed
   */
  private void handOverToItsLane(
      final MongoCollection<Document> collection,
      final Document entry) {

    final var held = lease.renewWhile(entry.getString("_id"), (
        renewed,
        leaseEnd) -> renewLease(collection, renewed, leaseEnd));
    try {
      lanes.runInOrderOf(orderingKeyOf(entry), () -> dispatch(collection, entry, held));
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
      final Document entry) {

    return entry.getString("aggregateId") == null
        ? "%s|%s".formatted(entry.getString("workflowModuleId"), entry.getString("bpmnProcessId"))
        : "%s|%s|%s"
            .formatted(
                entry.getString("workflowModuleId"), entry.getString("bpmnProcessId"), entry
                    .getString("aggregateId"));

  }

  /**
   * Dispatches a single claimed entry through the core's {@link PhaseTwoRouter}. On
   * success the entry is marked DONE; on failure it stays claimed and is retried
   * after the configured backoff, until it is blocked.
   *
   * <p>
   * The renewal ends here, before the write which says how the attempt ended. That write needs
   * a lease which has not run out, which the last renewal gave it, and not one which is still
   * growing - and a renewal outliving the write would find the entry no longer OPEN and report
   * a lease it never lost.
   *
   * @param collection The outbox collection
   * @param entry The claimed entry (holding the state before it was claimed)
   * @param held The renewal which started with the claim, closed when this attempt is over
   */
  private void dispatch(
      final MongoCollection<Document> collection,
      final Document entry,
      final DispatchLease.Held held) {

    final var entryId = entry.getString("_id");
    final String payloadReference;
    // everything the attempt does is inside, so the renewal is let go whichever way the
    // attempt ends - reading the arguments of the entry included
    try (held) {
      final var argsDocument = entry.get("args", Document.class);
      final Map<String, String> args = new LinkedHashMap<>();
      if (argsDocument != null) {
        argsDocument.forEach((
            key,
            value) -> args.put(key, String.valueOf(value)));
      }
      // a document which was taken before is one whose dispatch may have reached the
      // BPMS already (recovered/retried): the router
      // then runs the START re-dispatch mitigation. The operation travels as its
      // persisted name and is resolved by the router's operation registry
      // the one extra read this form costs, and only for an entry which names a
      // payload: a lookup by _id, once per dispatch attempt
      payloadReference = args.get(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
      final var payload = payloadReference == null
          ? null
          : getPayloadStore().read(payloadReference);
      dispatchMeasuringTheWait(
          PhaseTwoCall
              .forDispatch(
                  entry.getString("operation"), entry.getString("workflowModuleId"), entry
                      .getString("bpmnProcessId"),
                  entry.getString("aggregateId"), entry
                      .getString("adapterId"),
                  args, payload),
          wasTakenBefore(entry),
          entry.getDate("createdAt"));
    } catch (final RuntimeException e) {
      reportFailedDispatch(collection, entry, e);
      return;
    }
    final var markedDone = Updates.combine(
        Updates.set("status", MongoPhaseTwoOutbox.STATUS_DONE),
        Updates.set("doneAt", Date.from(Instant.now())),
        // the deduplication window ends with the dispatch: the entry's own id
        // takes the place of the key, which stays readable in idempotencyKey
        Updates.set("dedupKey", entryId),
        // the attempt is counted where it ended, and the lease given back
        Updates.inc("attempts", 1),
        Updates.unset("leasedBy"),
        Updates.unset("leasedUntil"));
    try {
      if (!writeAsTheHolder(collection, entry, markedDone)) {
        // the entry belongs to another node, and so do its bytes: that node may still be
        // dispatching and would find a payload which is gone
        return;
      }
    } catch (final RuntimeException e) {
      // the dispatch got through and the mark did not, so the entry says nothing about what
      // happened. It is planned again, which repeats the operation - the at-least-once the
      // contract names. Said out loud through the same report a failed dispatch uses,
      // because a mark lost in silence leaves an operation looking undone with nobody to ask
      reportFailedDispatch(collection, entry, e);
      return;
    }
    // the entry is dispatched, so its bytes have done their work. Removed AFTER the
    // entry was marked, never before: a crash in between leaves a document the
    // housekeeping deletes, while the other order would leave an entry whose payload
    // is gone
    if (payloadReference != null) {
      getPayloadStore().remove(payloadReference);
    }

  }

  /**
   * Writes down what a failed dispatch means for the entry: blocked where repeating cannot
   * help or where the attempts are used up, and a new due time otherwise.
   *
   * @param collection The outbox collection
   * @param entry The entry whose dispatch failed
   * @param e What the dispatch threw
   */
  private void reportFailedDispatch(
      final MongoCollection<Document> collection,
      final Document entry,
      final RuntimeException e) {

    final var entryId = entry.getString("_id");
    // the adapter said that repeating cannot help - blocked right away
    // instead of after the configured attempts
    if (io.vanillabp.integration.spi.PhaseTwoPermanentFailure.isPermanent(e)) {
      if (!writeAsTheHolder(collection, entry, blockEntry(entryId))) {
        return;
      }
      countBlockedEntry(entry.getString("operation"), true);
      log.error(
          "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "failed for a reason repeating cannot fix - the outbox entry '{}' is blocked and has "
              + "to be cleaned up manually!",
          entry.getString("operation"),
          entry.getString("bpmnProcessId"),
          entry.getString("workflowModuleId"),
          entry.getString("aggregateId"),
          entryId,
          e);
      return;
    }
    if (entry.getInteger("attempts") + 1 >= properties.getBlockAfterAttempts()) {
      if (!writeAsTheHolder(collection, entry, blockEntry(entryId))) {
        return;
      }
      countBlockedEntry(entry.getString("operation"), false);
      log.error(
          "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "failed {} times - the outbox entry '{}' is now blocked and has to be cleaned up manually!",
          entry.getString("operation"),
          entry.getString("bpmnProcessId"),
          entry.getString("workflowModuleId"),
          entry.getString("aggregateId"),
          entry.getInteger("attempts") + 1,
          entryId,
          e);
      return;
    }
    final var retryAfter = io.vanillabp.integration.spi.PhaseTwoRetryLater.retryAfter(e);
    if (retryAfter != null) {
      // the dispatch knows when asking again can help - a workflow the BPMS has not
      // made searchable yet is the case - so the entry waits that long instead of the
      // configured backoff. What ends a reason which never goes away is the attempts
      // counted above, not this due time
      if (!writeAsTheHolder(collection, entry, dueAgainAt(Instant.now().plus(retryAfter)))) {
        return;
      }
      log.info(
          "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' cannot "
              + "run yet - the outbox entry '{}' is dispatched again in {} ({} of {} attempts used): {}",
          entry.getString("operation"),
          entry.getString("bpmnProcessId"),
          entry.getString("workflowModuleId"),
          entry.getString("aggregateId"),
          entryId,
          retryAfter,
          entry.getInteger("attempts") + 1,
          properties.getBlockAfterAttempts(),
          e.getMessage());
    } else {
      // the attempts of the entry are the ones which ended before this one, so
      // attemptDelay(0) is the distance after the first failure: close, because most
      // failures are momentary
      final var retryIn = properties.attemptDelay(entry.getInteger("attempts"));
      if (!writeAsTheHolder(collection, entry, dueAgainAt(Instant.now().plus(retryIn)))) {
        return;
      }
      log.warn(
          "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "failed - the outbox entry '{}' is dispatched again in {} ({} of {} attempts used)",
          entry.getString("operation"),
          entry.getString("bpmnProcessId"),
          entry.getString("workflowModuleId"),
          entry.getString("aggregateId"),
          entryId,
          retryIn,
          entry.getInteger("attempts") + 1,
          properties.getBlockAfterAttempts(),
          e);
    }

  }

  /**
   * Writes down how an attempt ended, on the entry this node holds. The name of this node is
   * in the condition next to the id, so a node whose lease was taken over writes nothing: the
   * node holding the entry is carrying the same operation out, and its answer is the one the
   * collection keeps. The most expensive write to lose that race is a block over an entry the
   * other node has just dispatched, because somebody would be asked to repair an operation
   * which succeeded.
   *
   * @param collection The outbox collection
   * @param entry The entry this attempt ran on
   * @param update What to write on it
   * @return Whether this node still held the entry, so the write took
   */
  private boolean writeAsTheHolder(
      final MongoCollection<Document> collection,
      final Document entry,
      final org.bson.conversions.Bson update) {

    final var written = collection.updateOne(
        Filters
            .and(
                Filters.eq("_id", entry.getString("_id")),
                Filters.eq("leasedBy", lease.owner())),
        update);
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
  private static void reportResultOfALostEntry(
      final Document entry) {

    log
        .warn(
            "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' ended, but "
                + "the outbox entry '{}' belongs to another node by now - the result of this dispatch was "
                + "dropped and the entry says what that node wrote",
            entry.getString("operation"),
            entry.getString("bpmnProcessId"),
            entry.getString("workflowModuleId"),
            entry.getString("aggregateId"),
            entry.getString("_id"));

  }

  /**
   * Whether a dispatch has had this entry before, which is what makes a START probe the BPMS
   * it was meant for instead of starting a second workflow. An attempt which ended is
   * counted; an attempt whose node died in the middle left its name on the document and
   * nothing else, and <code>findOneAndUpdate</code> answers with the document as it was
   * BEFORE the claim, so that name is the one of the node before this one.
   *
   * @param entry The claimed document, as it stood before the claim
   * @return Whether an attempt ended or a holder disappeared
   */
  private static boolean wasTakenBefore(
      final Document entry) {

    return (entry.getInteger("attempts") > 0) || (entry.getString("leasedBy") != null);

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
      final Date writtenAt) {

    try {
      phaseTwoRouter
          .get()
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
      final Date writtenAt,
      final DispatchOutcome outcome) {

    if (writtenAt == null) {
      return;
    }
    io.vanillabp.integration.runtime.processservice.PhaseTwoRouterProducer
        .vanillaBpMetricsOf(vanillaBpMetrics)
        .outboxDispatchEnded(
            MongoPhaseTwoOutbox.class.getSimpleName(),
            outcome,
            io.vanillabp.integration.spi.PhaseTwoOutbox
                .waitedSince(writtenAt.toInstant())
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

    io.vanillabp.integration.runtime.processservice.PhaseTwoRouterProducer
        .vanillaBpMetricsOf(vanillaBpMetrics)
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
  private static org.bson.conversions.Bson dueAgainAt(
      final Instant nextAttempt) {

    return Updates
        .combine(
            Updates.set("nextAttemptAt", Date.from(nextAttempt)),
            Updates.inc("attempts", 1),
            Updates.unset("leasedBy"),
            Updates.unset("leasedUntil"));

  }

  /**
   * Blocks an entry and releases its <code>dedupKey</code> the way a dispatched entry
   * releases it. Easy to miss and the reason a blocked entry used to be a dead end: the
   * key is what refuses a second schedule of the same operation, so a blocked entry
   * which kept it would silence the very repetition the application needs - it would
   * ask, the outbox would answer no, and that answer looks exactly like a correct
   * deduplication. The row stays for whoever repairs it, and the new attempt of the
   * operation is a document of its own.
   *
   * @param entryId The id of the entry to block
   * @return The update to apply
   */
  private static org.bson.conversions.Bson blockEntry(
      final String entryId) {

    return Updates
        .combine(
            Updates.set("status", MongoPhaseTwoOutbox.STATUS_BLOCKED),
            Updates.set("dedupKey", entryId),
            // the attempt which led here is counted, and the lease given back
            Updates.inc("attempts", 1),
            Updates.unset("leasedBy"),
            Updates.unset("leasedUntil"));

  }

}
