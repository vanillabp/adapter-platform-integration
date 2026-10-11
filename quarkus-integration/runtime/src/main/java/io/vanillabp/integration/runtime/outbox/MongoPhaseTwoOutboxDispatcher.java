package io.vanillabp.integration.runtime.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalLong;

import org.bson.Document;
import org.bson.conversions.Bson;
import org.eclipse.microprofile.config.ConfigProvider;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfig;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.mongo.MongoSchema;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.DispatchOutcome;
import io.vanillabp.integration.adapter.migration.outbox.AStoppingNode;
import io.vanillabp.integration.adapter.migration.outbox.DispatchLanes;
import io.vanillabp.integration.adapter.migration.outbox.DispatchLease;
import io.vanillabp.integration.adapter.migration.outbox.DueEntryPoller;
import io.vanillabp.integration.adapter.migration.outbox.LastFailure;
import io.vanillabp.integration.adapter.migration.outbox.OutboxHousekeeping;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterPropertiesMapper;
import io.vanillabp.integration.runtime.deployment.VanillaBpDeploymentRunner;
import io.vanillabp.integration.runtime.mongo.MongoIndexes;
import io.vanillabp.integration.runtime.processservice.PhaseTwoRouterProducer;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoPermanentFailure;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches the entries of the MongoDB phase-two outbox (see {@link MongoPhaseTwoOutbox})
 * which are committed but not processed. It sends them through the core's
 * {@link PhaseTwoRouter}:
 * <ul>
 * <li>right after a commit ({@link MongoPhaseTwoOutbox} triggers this), and</li>
 * <li>through a poller for crash recovery and retries, which starts on
 * {@link StartupEvent}. The poller does not poll at a fixed rhythm. It sleeps until the
 * earliest entry is due which this store still owes something. For work which another node
 * wrote down before it died, <code>vanillabp.outbox.poll-interval</code> is the longest
 * sleep (see {@link DueEntryPoller}).</li>
 * </ul>
 * Due entries (status {@link MongoPhaseTwoOutbox#STATUS_OPEN}) are claimed atomically. A
 * <code>findOneAndUpdate</code> writes <code>leasedBy</code> and <code>leasedUntil</code>,
 * and the lease lasts one <code>vanillabp.outbox.attempt-frequency</code>. So several
 * application instances (pods) may poll at the same time without any distributed lock.
 * Exactly one instance wins each claim. <strong>A running dispatch renews its
 * lease</strong> ({@link DispatchLease}). So an entry which travels longer than that time
 * stays the claim of the node which carries it. The next poll does not take it. A node
 * which dies stops renewing, and another node takes the entry over once the lease has run
 * out. Every write which says how an attempt ended names the holder of the lease. So a node
 * which lost its entry writes nothing over what the holding node wrote.
 * <code>attempts</code> counts the attempts which ENDED. So a slow dispatch uses up no
 * attempts. A dispatch which FAILS writes the next attempt itself, at the growing distance
 * of
 * {@link io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties#attemptDelay(int)}
 * (it doubles per attempt, up to <code>vanillabp.outbox.max-attempt-frequency</code>).
 * After a successful dispatch, the entry is marked {@link MongoPhaseTwoOutbox#STATUS_DONE}. It
 * is kept until <code>vanillabp.outbox.retention</code> has passed, so support can read it.
 * After <code>vanillabp.outbox.block-after-attempts</code> failed attempts, it is marked
 * {@link MongoPhaseTwoOutbox#STATUS_BLOCKED} and waits for a person, see
 * {@link PhaseTwoOutboxProperties#BLOCKED_ENTRIES_GUIDE}.
 * <p>
 * <strong>An entry says why its last attempt did not get through.</strong> Every write which
 * ends an attempt sets <code>lastFailure</code>. It holds the reason where the attempt failed
 * or was answered with "not yet", and nothing where it went through.
 * {@link io.vanillabp.integration.adapter.migration.outbox.LastFailure} builds the text. So it
 * reads the same as the column of the JDBC table.
 * <p>
 * <strong>Waiting for a read model is not an attempt.</strong> If the adapter answers a
 * dispatch with {@link io.vanillabp.integration.spi.PhaseTwoRetryLater}, the entry is written
 * back with the window which the adapter named. <code>attempts</code> stays as it was. Time
 * ends that wait. Once <code>vanillabp.outbox.wait-for-visibility-at-most</code> has passed
 * since the entry was written, the entry is blocked (see decision 113 in the repository's
 * DECISIONS.md).
 * <p>
 * Unless <code>vanillabp.outbox.create-schema</code> is switched off, the indexes of
 * {@link MongoSchema#OUTBOX_INDEXES} and {@link MongoSchema#PAYLOAD_INDEXES} are created at
 * startup. The unique index over <code>dedupKey</code> does the deduplication. That field
 * holds the idempotency key only while the entry waits for its dispatch. So it deduplicates
 * the planned operations, not the ones which already reached the BPMS (see
 * {@link MongoPhaseTwoOutbox}). Marking an entry DONE writes its id there. Where the
 * application manages its schema itself, the startup reads what the collections have. It
 * names every missing index, with the statement which creates it. Earlier versions created
 * a sparse unique index over <code>idempotencyKey</code>. It is dropped where it is still
 * there, because it would deduplicate dispatched entries as well.
 * <p>
 * The database comes from <code>quarkus.mongodb.database</code>. It is the same database
 * which the application's aggregates live in.
 */
@ApplicationScoped
@Slf4j
public class MongoPhaseTwoOutboxDispatcher implements OutboxHousekeeping.Store {

  @Inject
  Instance<MongoClient> mongoClient;

  @Inject
  Instance<PhaseTwoRouter> phaseTwoRouter;

  /**
   * Where a blocked entry is counted. It is unsatisfied where the application uses no
   * Micrometer extension. That is why it is resolved through the producer's helper and not
   * injected directly.
   */
  @Inject
  Instance<VanillaBpMetrics> vanillaBpMetrics;

  @Inject
  TransactionSynchronizationRegistry txRegistry;

  private volatile PhaseTwoOutboxProperties properties;

  private volatile MongoPhaseTwoPayloadStore payloadStore;

  /**
   * The CDI container creates it, next to the outbox it dispatches for. The poller starts on
   * the startup event and not here, because a dispatch must not run before the BPMN
   * resources were deployed.
   */
  public MongoPhaseTwoOutboxDispatcher() {
  }

  /**
   * The outbox configuration (<code>vanillabp.outbox.*</code>), loaded lazily. So
   * {@link MongoPhaseTwoOutbox} can resolve its collection even before the startup event was
   * observed.
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
   * The threads which the entries are dispatched on, and the rule that the entries of one
   * workflow aggregate share one of them. It is created with the poller, because both need
   * the configuration.
   */
  private volatile DispatchLanes lanes;

  /**
   * What a running dispatch holds its entry with, and what keeps that hold alive while it
   * runs. It is created with the poller, because both need the configuration.
   */
  private volatile DispatchLease lease;

  /**
   * What removes the dispatched entries and the orphaned payloads, and when. It is created
   * with the poller, because both need the configuration.
   */
  private volatile OutboxHousekeeping housekeeping;

  private volatile MongoHousekeepingLease housekeepingLease;

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

    // What each index is read by is described once, in the core, because the Spring Boot
    // integration creates the same ones. For the index over the payload references, see
    // decision 76 in the repository's DECISIONS.md.
    if (properties.isCreateSchema()) {
      MongoIndexes.createOn(outboxCollection(), MongoSchema.OUTBOX_INDEXES);
      MongoIndexes.createOn(payloadCollection(), MongoSchema.PAYLOAD_INDEXES);
      dropLegacyIdempotencyKeyIndex();
    } else {
      // The collections themselves need no check. MongoDB creates one with the first
      // document. So an application which manages its own schema only owes the indexes.
      MongoIndexes.reportMissingOn(outboxCollection(), MongoSchema.OUTBOX_INDEXES);
      MongoIndexes.reportMissingOn(payloadCollection(), MongoSchema.PAYLOAD_INDEXES);
    }

    lease = new DispatchLease("vanillabp-outbox-lease", properties.getAttemptFrequency());
    lanes = new DispatchLanes("vanillabp-outbox-dispatch", properties.getDispatchThreads());
    poller = new DueEntryPoller(
        "vanillabp-outbox", properties.getPollInterval(), this::poll, this::earliestDueAt);
    poller.start();
    housekeeping = new OutboxHousekeeping(
        this, properties, () -> PhaseTwoRouterProducer
            .vanillaBpMetricsOf(vanillaBpMetrics));
    housekeeping.start();

  }

  /**
   * Stops the poller, the lanes and the renewal of the leases. What a lane was still holding
   * stays OPEN in the collection and keeps its lease until it runs out. Then another node
   * takes the entry.
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
    if (housekeeping != null) {
      housekeeping.stop();
      housekeeping = null;
    }

  }

  /**
   * Removes the sparse unique index over <code>idempotencyKey</code> which earlier versions
   * created. It deduplicated dispatched entries as well, and this store stopped doing that.
   * An index which is not there any more is not an error.
   */
  private void dropLegacyIdempotencyKeyIndex() {

    try {
      outboxCollection().dropIndex(Indexes.ascending("idempotencyKey"));
      log.info(
          "Dropped the outbox' unique index over 'idempotencyKey': deduplication now spans the "
              + "entries still waiting for their dispatch and uses 'dedupKey'");
    } catch (final RuntimeException e) {
      // 27 = IndexNotFound, which is the normal case.
      log.debug("No legacy unique index over 'idempotencyKey' to drop", e);
    }

  }

  /**
   * Moves the next poll forward to now. It is used right after a commit, because the entry
   * which was just written should go out at once.
   */
  public void triggerPoll() {

    final var running = poller;
    if (running != null) {
      running.somethingIsDueAt(Instant.now());
    }

  }

  /**
   * When this store owes something. It is the due time of the earliest entry which waits for
   * its dispatch, or the moment when the oldest dispatched entry may be deleted, whichever
   * comes first. A BLOCKED entry is in neither set. It waits for a person and not for a
   * clock, so it must not keep the poller awake.
   * <p>
   * An entry which is being dispatched right now answers with the end of its lease and not
   * with "due". The claim and every renewal move <code>nextAttemptAt</code> together with
   * <code>leasedUntil</code>. Otherwise the poller would hear "due now" for as long as the
   * dispatch runs, and it would ask again after its shortest sleep.
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
      // The next poll reports the same problem with its own message. And a poller which
      // stops asking is worse than one which asks at the configured limit.
      log.debug("Could not read when the next phase-two outbox entry is due", e);
      return null;
    }

  }

  /**
   * The smallest value of one field among the documents which a filter matches. It is read as
   * one document and not as an aggregation. So the index which this store creates over the
   * status and that field serves the sort.
   *
   * @param collection The outbox collection
   * @param filter What to look at
   * @param field The field to order by and to read
   * @return The value or <code>null</code> where nothing matches
   */
  Instant earliest(
      final MongoCollection<Document> collection,
      final Bson filter,
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
   * The collection which stores the outbox entries, in the database configured by
   * <code>quarkus.mongodb.database</code>.
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
   * The database which the outbox and its payloads live in. It is the one which the workflow
   * aggregates live in.
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
   * The collection which holds the payloads of the calls that have one
   * (<code>vanillabp.outbox.mongo.payload-collection</code>), in the database configured by
   * <code>quarkus.mongodb.database</code>.
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
   * Where the bytes of a call with a payload lie while its entry waits.
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
   * This thread does the claim, and a lane does the dispatch. The order matters. An entry is
   * claimed before it is handed over. So the claim of the next poll, here or on another node,
   * finds it leased and leaves it alone.
   * <p>
   * A lane whose queue is full makes this thread wait. Waiting is the back pressure of a
   * backlog which arrives faster than it leaves. It keeps the backlog in the collection,
   * where it can be read.
   */
  private synchronized void poll() {

    try {
      final var collection = outboxCollection();
      while (true) {
        final var now = Instant.now();
        // Claim atomically: write this node's name and the end of the lease. So other
        // instances skip the entry while the dispatch runs and renews. A free lease makes an
        // entry available, never the number of attempts, which says what ENDED.
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
            // The oldest entry first, because the lanes need that. They keep the order in
            // which they get the entries of one aggregate. A claim in whatever order the
            // collection answers would hand them over the wrong way round. The index over the
            // status and that moment serves the sort.
            new FindOneAndUpdateOptions().sort(Sorts.ascending("createdAt")));
        if (entry == null) {
          break;
        }
        handOverToItsLane(collection, entry);
      }
    } catch (final RuntimeException e) {
      log.error("Polling the VanillaBP phase-two outbox failed - will retry", e);
    }

  }

  /**
   * Moves the lease of an entry which this node is dispatching forward. It is a write of its
   * own, once per tick of {@link DispatchLease}.
   *
   * @param collection The outbox collection
   * @param entryId The entry being dispatched
   * @param leaseEnd How long the lease is to last now
   * @return Whether this node still holds the entry. <code>false</code> where the write
   *         matched no document. Then the entry is gone or somebody else holds it
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
      // A collection which cannot be asked says nothing about who holds the entry. So the
      // renewal keeps trying and does not give the entry up over one short failure.
      log.warn("Could not renew the lease of the phase-two outbox entry '{}' - trying again", entryId, e);
      return true;
    }

  }

  /**
   * Hands the entry which the poll has just claimed to the lane of its aggregate. The renewal
   * of its lease is already running.
   * <p>
   * The renewal starts here and not where the lane picks the entry up. Between the two, the
   * entry waits in the lane's queue. A wait longer than the lease would let another node
   * claim an entry which this node is about to dispatch. Then both would carry the operation
   * out.
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
      // Nothing will dispatch this entry, so nothing would close the renewal either.
      held.close();
      throw e;
    }

  }

  /**
   * What an entry is ordered by: its workflow aggregate. An entry which names none, like a
   * broadcast signal, is ordered by its BPMN process instead. So two signals of one process
   * still leave in the order in which they were planned.
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
   * a lease which has not run out. The last renewal gave it one. It does not need a lease
   * which still grows. And a renewal which lived longer than the write would find the entry no
   * longer OPEN. It would then report a lease which it never lost.
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
    // Everything which the attempt does is inside, so the renewal is let go whichever way
    // the attempt ends. This includes reading the arguments of the entry.
    try (held) {
      final var argsDocument = entry.get("args", Document.class);
      final Map<String, String> args = new LinkedHashMap<>();
      if (argsDocument != null) {
        argsDocument.forEach((
            key,
            value) -> args.put(key, String.valueOf(value)));
      }
      // A document which was taken before is one whose dispatch may have reached the
      // BPMS already (recovered or retried). Then the router runs the guard against a
      // repeated START. The operation travels as its persisted name, and the router's
      // operation registry resolves it.
      // The payload costs one extra read, and only for an entry which names a payload:
      // a lookup by _id, once per dispatch attempt.
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
        // The deduplication window ends with the dispatch. The entry's own id takes the
        // place of the key, which stays readable in idempotencyKey.
        Updates.set("dedupKey", entryId),
        // The attempt is counted where it ended, and the lease is given back.
        Updates.inc("attempts", 1),
        Updates.unset("leasedBy"),
        Updates.unset("leasedUntil"),
        // The attempt which ended last went through, so there is no failure to name.
        Updates.unset("lastFailure"));
    try {
      if (!writeAsTheHolder(collection, entry, markedDone)) {
        // The entry belongs to another node, and so do its bytes. That node may still be
        // dispatching and would find a payload which is gone.
        return;
      }
    } catch (final RuntimeException e) {
      // The dispatch got through, but the mark did not. So the entry says nothing about
      // what happened. It is planned again, and that repeats the operation. This is the
      // at-least-once which the contract names. We report it like a failed dispatch. A
      // mark lost without a word would leave an operation which looks undone, with nobody
      // to ask.
      reportFailedDispatch(collection, entry, e);
      return;
    }
    // The entry is dispatched, so its bytes have done their work. They are removed AFTER
    // the entry was marked, never before. A crash in between leaves a document which the
    // housekeeping deletes. The other order would leave an entry whose payload is gone.
    if (payloadReference != null) {
      getPayloadStore().remove(payloadReference);
    }

  }

  /**
   * Writes down what a failed dispatch means for the entry. A write which cannot get through
   * stays inside this lane.
   * <p>
   * The path which marks an entry DONE is guarded the same way. What the store throws there
   * ends as a report. It does not leave the runnable of a lane as an exception. There it
   * would be printed as "Exception in thread vanillabp-outbox-dispatch-1", with a stack trace
   * which nobody can place. The failure path used to be the one without a guard. And a
   * shutdown during a dispatch is exactly when that matters.
   *
   * @param collection The outbox collection
   * @param entry The entry whose dispatch failed
   * @param e What the dispatch threw
   */
  private void reportFailedDispatch(
      final MongoCollection<Document> collection,
      final Document entry,
      final RuntimeException e) {

    try {
      writeDownTheFailedDispatch(collection, entry, e);
    } catch (final RuntimeException whileWritingItDown) {
      reportAResultWhichWasNotWritten(entry, whileWritingItDown);
    }

  }

  /**
   * Says that the end of an attempt was not written down, and says which of the two cases it
   * is.
   * <p>
   * Nothing is lost either way. The entry stays as it was and keeps its lease. The next node
   * which picks it up dispatches it again once that lease runs out. This is the at-least-once
   * which the contract names. What differs is who has to do something. A node which is being
   * STOPPED is the ordinary case of {@code JdbcPhaseTwoOutboxDispatcher#reportTheEntryNoLaneTook}.
   * It is logged at INFO. A stack trace there would send an operator looking for a fault where
   * there was only a deployment. Everything else keeps the level it had.
   * <p>
   * The interrupt flag is set again before the line is written. The work of this lane is
   * over, and whoever asks the thread next has to see that it was interrupted.
   *
   * @param entry The entry this attempt ran on
   * @param e What the store threw while the result was being written
   */
  private static void reportAResultWhichWasNotWritten(
      final Document entry,
      final RuntimeException e) {

    if (AStoppingNode.isTheReasonFor(e)) {
      Thread.currentThread().interrupt();
      log.info(
          "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' was "
              + "interrupted because this node is stopping - the outbox entry '{}' stays as it is and is "
              + "dispatched once its lease runs out",
          entry.getString("operation"),
          entry.getString("bpmnProcessId"),
          entry.getString("workflowModuleId"),
          entry.getString("aggregateId"),
          entry.getString("_id"));
      return;
    }
    log.error(
        "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
            + "failed, and how it failed could not be written down either - the outbox entry '{}' stays "
            + "as it is and is dispatched once its lease runs out!",
        entry.getString("operation"),
        entry.getString("bpmnProcessId"),
        entry.getString("workflowModuleId"),
        entry.getString("aggregateId"),
        entry.getString("_id"),
        e);

  }

  /**
   * Writes down what a failed dispatch means for the entry. It is blocked where repeating
   * cannot help, where the attempts are used up, or where the entry waited too long for its
   * BPMS. Otherwise it gets a new due time.
   *
   * @param collection The outbox collection
   * @param entry The entry whose dispatch failed
   * @param e What the dispatch threw
   */
  private void writeDownTheFailedDispatch(
      final MongoCollection<Document> collection,
      final Document entry,
      final RuntimeException e) {

    final var entryId = entry.getString("_id");
    // The adapter said that repeating cannot help. So the entry is blocked at once, not
    // after the configured attempts.
    if (PhaseTwoPermanentFailure.isPermanent(e)) {
      if (!writeAsTheHolder(collection, entry, blockEntry(entryId, LastFailure.of(e)))) {
        return;
      }
      countBlockedEntry(entry.getString("operation"), true);
      log.error(
          "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "failed for a reason repeating cannot fix - the outbox entry '{}' is blocked. How to find "
              + "it, open it again or delete it: "
              + PhaseTwoOutboxProperties.BLOCKED_ENTRIES_GUIDE,
          entry.getString("operation"),
          entry.getString("bpmnProcessId"),
          entry.getString("workflowModuleId"),
          entry.getString("aggregateId"),
          entryId,
          e);
      return;
    }
    final var retryAfter = PhaseTwoRetryLater.retryAfter(e);
    if (retryAfter != null) {
      waitForTheReadModel(collection, entry, retryAfter, e);
      return;
    }
    if (entry.getInteger("attempts") + 1 >= properties.getBlockAfterAttempts()) {
      if (!writeAsTheHolder(collection, entry, blockEntry(entryId, LastFailure.of(e)))) {
        return;
      }
      countBlockedEntry(entry.getString("operation"), false);
      log.error(
          "Dispatching phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' "
              + "failed {} times - the outbox entry '{}' is now blocked. How to find it, open it again or "
              + "delete it: "
              + PhaseTwoOutboxProperties.BLOCKED_ENTRIES_GUIDE,
          entry.getString("operation"),
          entry.getString("bpmnProcessId"),
          entry.getString("workflowModuleId"),
          entry.getString("aggregateId"),
          entry.getInteger("attempts") + 1,
          entryId,
          e);
      return;
    }
    // The attempts of the entry are the ones which ended before this one. So
    // attemptDelay(0) is the distance after the first failure. It is short, because most
    // failures last only a moment.
    final var retryIn = properties.attemptDelay(entry.getInteger("attempts"));
    if (!writeAsTheHolder(collection, entry, dueAgainAt(Instant.now().plus(retryIn), LastFailure.of(e)))) {
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

  /**
   * Writes down that the BPMS of the entry does not report its workflow yet. The entry waits
   * for the window which the adapter named, not for the configured backoff. The attempt is
   * not counted, because a read model which is behind is no failure of the entry. A wait
   * which never ends is ended by the time since the entry was written:
   * <code>vanillabp.outbox.wait-for-visibility-at-most</code>.
   *
   * @param collection The outbox collection
   * @param entry The entry whose dispatch was answered with "not yet"
   * @param retryAfter The window the adapter named
   * @param e What the dispatch threw
   */
  private void waitForTheReadModel(
      final MongoCollection<Document> collection,
      final Document entry,
      final Duration retryAfter,
      final RuntimeException e) {

    final var entryId = entry.getString("_id");
    final var createdAt = entry.getDate("createdAt") == null
        ? null
        : entry.getDate("createdAt").toInstant();
    final var now = Instant.now();
    if (properties.hasWaitedForVisibilityLongEnough(createdAt, now)) {
      final var reason = LastFailure.ofAWaitWhichRanOut(Duration.between(createdAt, now), e);
      if (!writeAsTheHolder(collection, entry, blockEntry(entryId, reason))) {
        return;
      }
      countBlockedEntry(entry.getString("operation"), false);
      log.error(
          "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' waited {} "
              + "for its BPMS to report the workflow, which is longer than '{}' allows - the outbox "
              + "entry '{}' is now blocked. How to find it, open it again or delete it: "
              + PhaseTwoOutboxProperties.BLOCKED_ENTRIES_GUIDE
              + " - the last answer was: {}",
          entry.getString("operation"),
          entry.getString("bpmnProcessId"),
          entry.getString("workflowModuleId"),
          entry.getString("aggregateId"),
          Duration.between(createdAt, now),
          PhaseTwoOutboxProperties.WAIT_FOR_VISIBILITY_AT_MOST_PROPERTY,
          entryId,
          e.getMessage());
      return;
    }
    if (!writeAsTheHolder(collection, entry, dueAgainWithoutCountingAt(now.plus(retryAfter), LastFailure.of(e)))) {
      return;
    }
    log.info(
        "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' cannot "
            + "run yet - the outbox entry '{}' is dispatched again in {}, and it waits until {} at "
            + "most: {}",
        entry.getString("operation"),
        entry.getString("bpmnProcessId"),
        entry.getString("workflowModuleId"),
        entry.getString("aggregateId"),
        entryId,
        retryAfter,
        createdAt == null
            ? null
            : createdAt.plus(properties.waitForVisibilityAtMost()),
        e.getMessage());

  }

  /**
   * Writes down how an attempt ended, on the entry which this node holds. The name of this
   * node is in the condition next to the id. So a node whose lease was taken over writes
   * nothing. The node which holds the entry carries out the same operation, and the
   * collection keeps its answer. The worst write which could win that race would be a block
   * over an entry which the other node has just dispatched. Then somebody would be asked to
   * repair an operation which succeeded.
   *
   * @param collection The outbox collection
   * @param entry The entry this attempt ran on
   * @param update What to write on it
   * @return Whether this node still held the entry, so that the write took effect
   */
  private boolean writeAsTheHolder(
      final MongoCollection<Document> collection,
      final Document entry,
      final Bson update) {

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
   * Says that an attempt ended on an entry which this node does not hold any more. So what it
   * wanted to write was dropped.
   * <p>
   * The renewal said the same earlier, at the moment the entry changed hands. This message is
   * the other end of it and is worth its own line. It names the operation which ran twice. And
   * it proves that the second run did not overwrite what the holding node wrote.
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
   * Whether a dispatch has had this entry before. If so, a START asks the BPMS it was meant
   * for and does not start a second workflow. An attempt which ended is counted. An attempt
   * whose node died in the middle left only its name on the document.
   * <code>findOneAndUpdate</code> answers with the document as it was BEFORE the claim. So
   * that name is the one of the node before this one.
   *
   * @param entry The claimed document, as it stood before the claim
   * @return Whether an attempt ended or a holder disappeared
   */
  private static boolean wasTakenBefore(
      final Document entry) {

    return (entry.getInteger("attempts") > 0) || (entry.getString("leasedBy") != null);

  }

  /**
   * Hands the call to the core's router. It reports how long its entry waited for this
   * attempt, whether the attempt succeeded or threw. The wait is counted from the moment the
   * entry was written. So a repeated attempt reports the whole wait of the operation, not the
   * time since the last try.
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
   * @param writtenAt When the entry was written. It is <code>null</code> only for a document
   *          which somebody else wrote into the collection without that field. Its attempt
   *          is not measured at all, rather than measured from now
   * @param outcome How the attempt ended
   */
  private void reportWait(
      final Date writtenAt,
      final DispatchOutcome outcome) {

    if (writtenAt == null) {
      return;
    }
    PhaseTwoRouterProducer
        .vanillaBpMetricsOf(vanillaBpMetrics)
        .outboxDispatchEnded(
            MongoPhaseTwoOutbox.class.getSimpleName(),
            outcome,
            PhaseTwoOutbox
                .waitedSince(writtenAt.toInstant())
                .toNanos());

  }

  /**
   * Counts an entry which this store gave up on. The gauge of waiting entries drops at the
   * same moment. So without this counter, the only number which an operator watches would
   * move as if things had got better.
   *
   * @param operation The persisted name of the operation which was lost
   * @param permanent Whether the adapter said that repeating cannot help
   */
  private void countBlockedEntry(
      final String operation,
      final boolean permanent) {

    PhaseTwoRouterProducer
        .vanillaBpMetricsOf(vanillaBpMetrics)
        .outboxEntryBlocked(MongoPhaseTwoOutbox.class.getSimpleName(), operation, permanent);

  }

  /**
   * Says when an entry whose attempt did not get through is to be read again. It counts that
   * attempt and gives the lease back. All three happen in one write. Otherwise an entry which
   * is due again while it is still leased would wait until the end of the lease. The reason
   * goes into the same write. So an entry which is still being repeated says why it hangs.
   *
   * @param nextAttempt When it is to be read again
   * @param reason Why the attempt did not get through, as {@link LastFailure} words it
   * @return The update to apply
   */
  private static Bson dueAgainAt(
      final Instant nextAttempt,
      final String reason) {

    return Updates
        .combine(
            Updates.set("nextAttemptAt", Date.from(nextAttempt)),
            Updates.inc("attempts", 1),
            Updates.unset("leasedBy"),
            Updates.unset("leasedUntil"),
            Updates.set("lastFailure", reason));

  }

  /**
   * Says when the entry is to be read again after its BPMS did not report the workflow yet,
   * and gives the lease back. It is {@link #dueAgainAt(Instant, String)} without the count.
   * Waiting for a read model is not a failed attempt. Counting it blocked entries after a few
   * minutes of a stopped exporter. Instead, the time the entry waited is read from
   * <code>createdAt</code>. The answer is written as the last failure, because it is the
   * reason why the entry waits.
   *
   * @param nextAttempt When the entry is to be read again
   * @param reason The answer of the adapter, as {@link LastFailure} words it
   * @return The update to apply
   */
  private static Bson dueAgainWithoutCountingAt(
      final Instant nextAttempt,
      final String reason) {

    return Updates
        .combine(
            Updates.set("nextAttemptAt", Date.from(nextAttempt)),
            Updates.unset("leasedBy"),
            Updates.unset("leasedUntil"),
            Updates.set("lastFailure", reason));

  }

  /**
   * Blocks an entry and releases its <code>dedupKey</code>, the same way as a dispatched entry
   * releases it. This is easy to miss, and it is why a blocked entry used to be a dead end.
   * The key refuses a second schedule of the same operation. A blocked entry which kept it
   * would silence exactly the repetition which the application needs. The application would
   * ask, and the outbox would answer no. That answer looks exactly like a correct
   * deduplication. The row stays for whoever repairs it. The new attempt of the operation is
   * a document of its own. The reason goes into the same write. So whoever finds the document
   * reads why, without the log.
   *
   * @param entryId The id of the entry to block
   * @param reason Why it is blocked, as {@link LastFailure} words it
   * @return The update to apply
   */
  private static Bson blockEntry(
      final String entryId,
      final String reason) {

    return Updates
        .combine(
            Updates.set("status", MongoPhaseTwoOutbox.STATUS_BLOCKED),
            Updates.set("dedupKey", entryId),
            Updates.set("lastFailure", reason),
            // The attempt which led here is counted, and the lease is given back.
            Updates.inc("attempts", 1),
            Updates.unset("leasedBy"),
            Updates.unset("leasedUntil"));

  }


  @Override
  public String storeName() {

    return MongoPhaseTwoOutbox.class.getSimpleName();

  }

  @Override
  public boolean claimHousekeepingUntil(
      final String owner,
      final Instant until) {

    return housekeepingLease().claimUntil(collectionName(), owner, until);

  }

  @Override
  public void releaseHousekeeping(
      final String owner) {

    housekeepingLease().release(collectionName(), owner);

  }

  /**
   * {@inheritDoc}
   * <p>
   * A delete in MongoDB has no limit. So the ids of the entries which may go are read first,
   * at most as many as asked for. They go into one <code>deleteMany</code>. The index over
   * the status and the moment when an entry was dispatched serves the read.
   */
  @Override
  public int removeDispatchedEntriesOlderThan(
      final Instant threshold,
      final int maxRows) {

    if (maxRows < 1) {
      return 0;
    }
    final var collection = outboxCollection();
    final var expired = Filters
        .and(
            Filters.eq("status", MongoPhaseTwoOutbox.STATUS_DONE),
            Filters.lt("doneAt", Date.from(threshold)));
    final var ids = new ArrayList<Object>();
    collection
        .find(expired)
        .projection(Projections.include("_id"))
        .limit(maxRows)
        .forEach(document -> ids.add(document.get("_id")));
    if (ids.isEmpty()) {
      return 0;
    }
    return (int) collection
        .deleteMany(Filters.in("_id", ids))
        .getDeletedCount();

  }

  /**
   * {@inheritDoc}
   * <p>
   * The entries were removed first. So the payloads they named are named by nothing now, and
   * they go with this call. A payload which an entry still names is not removed by age at
   * all. Such an entry may wait, or it may be blocked until somebody repairs it. The payload
   * store asks the entries about that itself.
   */
  @Override
  public int removeOrphanedPayloadsOlderThan(
      final Instant threshold,
      final int maxRows) {

    return getPayloadStore().removeOrphansOlderThan(threshold, maxRows);

  }

  @Override
  public OptionalLong countDispatchedEntriesOlderThan(
      final Instant threshold) {

    try {
      return OptionalLong
          .of(outboxCollection()
              .countDocuments(
                  Filters
                      .and(
                          Filters.eq("status", MongoPhaseTwoOutbox.STATUS_DONE),
                          Filters.lt("doneAt", Date.from(threshold)))));
    } catch (final RuntimeException e) {
      // A number which could not be read stays a gap in the meter, not a zero.
      log.debug("Could not count the dispatched entries of the outbox collection", e);
      return OptionalLong.empty();
    }

  }

  /**
   * The name of the collection which this store polls. The housekeeping claims the store by
   * this name.
   *
   * @return The outbox collection name
   */
  private String collectionName() {

    return getProperties()
        .getMongo()
        .getCollection();

  }

  /**
   * Where this node says that it does the housekeeping of this store tonight. It is created
   * on first use, for the same reason as the payload store: the collection name is read
   * lazily.
   *
   * @return The claim of this outbox
   */
  private MongoHousekeepingLease housekeepingLease() {

    if (housekeepingLease == null) {
      housekeepingLease = new MongoHousekeepingLease(
          () -> mongoClient
              .get()
              .getDatabase(databaseName())
              .getCollection(getProperties()
                  .getMongo()
                  .getHousekeepingCollection()));
    }
    return housekeepingLease;

  }

}
