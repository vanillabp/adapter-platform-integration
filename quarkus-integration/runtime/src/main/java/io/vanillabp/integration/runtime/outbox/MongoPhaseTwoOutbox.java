package io.vanillabp.integration.runtime.outbox;

import java.time.Instant;
import java.util.UUID;

import org.bson.Document;

import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoClient;

import io.vanillabp.integration.runtime.processservice.PlatformDefaultStore;
import io.vanillabp.integration.runtime.processservice.QuarkusPersistenceTechnology;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * The default {@link PhaseTwoOutbox} implementation for Quarkus applications using
 * MongoDB (extension <code>quarkus-mongodb-client</code>) for aggregate persistence.
 * The entry persists the fields of the {@link PhaseTwoCall} including the operation
 * discriminator and the elected adapter ID; deduplication is enforced by a unique
 * index on the entry's <code>dedupKey</code> and therefore spans the entries still
 * waiting for their dispatch, as the contract of {@link PhaseTwoOutbox} demands: the
 * field carries the idempotency key while the entry waits and the entry's own id once
 * the dispatcher marked it DONE, while <code>idempotencyKey</code> keeps the key
 * readable for support. An entry without a key carries its id there right away, so the
 * field is never absent and the index needs no partial filter. What carries the
 * at-least-once guarantee is <code>status</code> and <code>attempts</code> of the
 * entry, never the key - a redispatch reads the same document.
 * The collection name matches the Spring Boot MongoDB outbox
 * (<code>vanillabp.outbox.mongo.collection</code>, default
 * <code>vanillabp-phase-two-outbox</code>) so both platforms share the same store
 * layout.
 * <p>
 * <strong>A younger call may take the waiting entry's place</strong> instead of being
 * discarded against it, where it says so
 * ({@link PhaseTwoCall#replacingWhatIsStillWaiting()}). The document keeps its id and
 * its key and gets everything the dispatch reads, and the payload of the entry it
 * replaced is removed with it. What decides is <code>attempts</code>: the dispatcher
 * counts the attempt when it claims an entry, so a zero there means no dispatch has
 * read this entry and none is holding its payload. The update carries that condition,
 * which makes it the same atomic claim - if a poller wins the document, the update
 * matches nothing and the call becomes an entry of its own, with <code>dedupKey</code>
 * set to its own id because the key belongs to the entry on its way.
 * <p>
 * <strong>One transaction where MongoDB Panache provides a session:</strong>
 * MongoDB Panache enlists itself in the Narayana transaction - it starts a
 * <code>ClientSession</code> with a MongoDB transaction and keeps it as a transaction
 * resource - and this outbox writes through that very session. Aggregate and outbox entry
 * then commit together, which is what the {@link PhaseTwoOutbox} contract demands, and the
 * entry becomes visible to anybody else only with the commit. The session needs the
 * deployment to be a replica set, and MongoDB Panache on the classpath.
 * <p>
 * <strong>Best-effort window (no MongoDB transaction):</strong> Without such a session -
 * an application using the MongoDB client without Panache, or a write outside any
 * transaction - MongoDB is no JTA resource, so the entry is written <i>immediately</i>,
 * before the local commit. Two windows follow:
 * <ul>
 * <li><i>Rollback:</i> the already-written entry would become an orphan and the
 * poller would start a workflow whose aggregate does not exist. Mitigation: on
 * {@link Status#STATUS_ROLLEDBACK} the entry is deleted best-effort; only a crash
 * between the insert and the rollback handling leaves an orphan behind (visible in
 * the outbox store as a repeatedly failing, finally BLOCKED entry).</li>
 * <li><i>Crash before commit:</i> the entry exists but the aggregate was never
 * committed - same as above, the dispatch fails repeatedly and the entry is
 * blocked with a monitorable ERROR.</li>
 * </ul>
 */
@ApplicationScoped
@Slf4j
public class MongoPhaseTwoOutbox implements PhaseTwoOutbox, PlatformDefaultStore {

  /**
   * An entry which still owes its call to the BPMS. The dispatcher next to this class
   * claims the entries in this state, and the relational outbox writes the same word
   * into its state column, so both stores are read the same way in a support case.
   */
  public static final String STATUS_OPEN = "OPEN";

  /**
   * An entry whose call reached the BPMS. It is kept until
   * <code>vanillabp.outbox.retention</code> passed, so support can still read what
   * happened.
   */
  public static final String STATUS_DONE = "DONE";

  /**
   * An entry which failed <code>vanillabp.outbox.block-after-attempts</code> times and
   * is not retried any more. Somebody has to look at it - with the defaults, that many
   * attempts span hours, so what ends up here is broken rather than waiting for a BPMS
   * which is away.
   */
  public static final String STATUS_BLOCKED = "BLOCKED";

  @Inject
  Instance<MongoClient> mongoClient;

  @Inject
  TransactionSynchronizationRegistry txRegistry;

  @Inject
  MongoPhaseTwoOutboxDispatcher dispatcher;

  /**
   * Built by the CDI container. The extension registers this bean whether or not the
   * application has a MongoDB client, so nothing may be read or opened here -
   * {@link #isAvailable()} decides later whether the bean is used at all.
   */
  public MongoPhaseTwoOutbox() {
  }

  @Override
  public QuarkusPersistenceTechnology.Technology technology() {

    return QuarkusPersistenceTechnology.Technology.MONGO;

  }

  /**
   * Whether this default outbox is usable: the extension registers the bean at
   * build time, but without a MongoDB client (and database) it cannot store
   * anything - an unusable default must not be selected for an aggregate (the
   * startup validation then reports "no outbox available" with the remedies
   * instead of failing at the first workflow start).
   *
   * @return Whether a MongoDB client is available
   */
  @Override
  public boolean isAvailable() {

    return mongoClient.isResolvable();

  }

  /**
   * The adapter ids the OPEN entries of one BPMN process are waiting for: an
   * id which is not configured any more means that it was renamed or removed too early,
   * and both leave the workflow of a START entry unstarted.
   */
  @Override
  public java.util.Set<String> adapterIdsOfPendingCalls(
      final String workflowModuleId,
      final String bpmnProcessId) {

    if (!isAvailable()) {
      return java.util.Set.of();
    }
    final var filter = new Document("workflowModuleId", workflowModuleId)
        .append("bpmnProcessId", bpmnProcessId)
        .append("status", STATUS_OPEN)
        .append("adapterId", new Document("$ne", null));
    final var adapterIds = new java.util.LinkedHashSet<String>();
    dispatcher
        .outboxCollection()
        .distinct("adapterId", filter, String.class)
        .forEach(adapterIds::add);
    return adapterIds;

  }

  /**
   * Counts the entries waiting for their dispatch - a single count over the same
   * collection the dispatcher polls.
   */
  @Override
  public java.util.OptionalLong pendingCalls() {

    if (!mongoClient.isResolvable()) {
      return java.util.OptionalLong.empty();
    }
    try {
      return java.util.OptionalLong
          .of(dispatcher
              .outboxCollection()
              .countDocuments(new Document("status", STATUS_OPEN)));
    } catch (final RuntimeException e) {
      // a metric must never be the reason an application fails
      log.debug("Could not count the pending entries of the MongoDB phase-two outbox", e);
      return java.util.OptionalLong.empty();
    }

  }

  /**
   * How long the oldest waiting entry has been waiting, read from its
   * <code>createdAt</code> - the moment the entry was written, which a replacing call
   * sets anew because the document then carries a younger operation. One document, read
   * along the index this store creates over the status and that moment.
   */
  @Override
  public java.util.Optional<java.time.Duration> ageOfOldestPendingCall() {

    if (!mongoClient.isResolvable()) {
      return java.util.Optional.empty();
    }
    try {
      final var oldest = dispatcher
          .earliest(
              dispatcher.outboxCollection(),
              com.mongodb.client.model.Filters.eq("status", STATUS_OPEN),
              "createdAt");
      // nothing waiting means nothing is owed, and that zero is a measurement
      return java.util.Optional
          .of(oldest == null
              ? java.time.Duration.ZERO
              : PhaseTwoOutbox.waitedSince(oldest));
    } catch (final RuntimeException e) {
      // a metric must never be the reason an application fails
      log.debug("Could not read the oldest pending entry of the MongoDB phase-two outbox", e);
      return java.util.Optional.empty();
    }

  }

  @Override
  public boolean schedule(
      final PhaseTwoCall call) {

    final var collection = dispatcher.outboxCollection();

    final var idempotencyKey = call.idempotencyKey().orElse(null);
    final var now = Instant.now();
    final var entryId = UUID.randomUUID().toString();
    // the session of the running transaction where MongoDB Panache provides one: the
    // entry then commits with the aggregate instead of being written immediately
    //
    final var session = io.vanillabp.integration.runtime.mongo.MongoSessions
        .activeSession(txRegistry);
    // within a MongoDB transaction a duplicate-key error would abort the whole
    // transaction (the aggregate included), so a duplicate is detected by a read - the
    // unique index stays the backstop for two nodes scheduling at once
    final var waiting = idempotencyKey == null
        ? null
        : (session == null
            ? collection.find(new Document("dedupKey", idempotencyKey)).first()
            : collection.find(session, new Document("dedupKey", idempotencyKey)).first());
    // what the unique index sees. An operation which must not be deduplicated dedupes
    // against itself, so the field is present on every entry and the index needs no
    // partial filter, and a second entry beside one a dispatch already took does the
    // same: the key belongs to the entry on its way
    var dedupKey = idempotencyKey == null ? entryId : idempotencyKey;
    if (waiting != null) {
      if (!call.replacesWhatIsStillWaiting()) {
        logDiscardedSchedule(call);
        return false;
      }
      if (replacePendingEntry(collection, session, waiting, call, now)) {
        triggerPollAfterCommit(session, call, entryId, true);
        return true;
      }
      // a poller claimed the entry between the read and the update: it runs to its end
      // and this call becomes an entry of its own
      logSecondEntryBesideAClaimedOne(call);
      dedupKey = entryId;
    }
    final var entry = new Document()
        .append("_id", entryId)
        .append("workflowModuleId", call.workflowModuleId())
        .append("bpmnProcessId", call.bpmnProcessId())
        .append("operation", call.operation())
        .append("aggregateId", call.workflowAggregateId())
        .append("adapterId", call.adapterId())
        .append("args", new Document(new java.util.LinkedHashMap<String, Object>(call.args())))
        .append("idempotencyKey", idempotencyKey)
        .append("dedupKey", dedupKey)
        .append("status", STATUS_OPEN)
        .append("createdAt", java.util.Date.from(now))
        .append("attempts", 0)
        .append("nextAttemptAt", java.util.Date.from(now));
    try {
      if (session != null) {
        collection.insertOne(session, entry);
      } else {
        collection.insertOne(entry);
      }
    } catch (final MongoWriteException e) {
      // 11000 = duplicate key: the idempotency key is already present
      if (e.getError().getCode() == 11000) {
        logDiscardedSchedule(call);
        return false;
      }
      throw e;
    }

    // the entry is in, so the bytes it names may follow - in the same session where
    // there is one, and only now, because a schedule discarded as a duplicate must
    // leave nothing behind
    if (call.hasPayload()) {
      dispatcher.getPayloadStore().write(call);
    }

    triggerPollAfterCommit(session, call, entryId, false);

    return true;

  }

  /**
   * A call which replaces goes the same way as any other, and this is the one method
   * which says so - the mark travels in the call, so {@link #schedule(PhaseTwoCall)}
   * reads it where the entry is written.
   */
  @Override
  public boolean scheduleReplacingWhatIsStillWaiting(
      final PhaseTwoCall call) {

    return schedule(call.replacingWhatIsStillWaiting());

  }

  /**
   * Puts the younger call into the document of the waiting entry, its payload
   * included, and removes the payload the replaced entry named.
   * <p>
   * What decides is the pair of the attempts and the lease: no attempt of this entry has
   * ended and nobody is dispatching it right now, so no dispatch has read it and none is
   * holding its payload. The update carries both conditions, which makes it the same atomic
   * claim the dispatcher uses - if a poller wins the document, the update matches nothing.
   * Both halves are needed, because the attempts are written when an attempt ends: a dispatch
   * which is on its way still shows zero of them and is named by the lease alone.
   * <p>
   * Where a session covers the writes they commit together and a rollback takes them
   * all. Without one they are three separate writes and the window between them is the
   * one an insert has in this store as well, which the class javadoc calls
   * best-effort.
   *
   * @return Whether the entry was replaced - <code>false</code> where a poller claimed
   *         it in the meantime, which makes the call an entry of its own
   */
  private boolean replacePendingEntry(
      final com.mongodb.client.MongoCollection<Document> collection,
      final com.mongodb.client.ClientSession session,
      final Document waiting,
      final PhaseTwoCall call,
      final Instant now) {

    if (waiting.getInteger("attempts", 0) > 0) {
      return false;
    }
    final var replacement = com.mongodb.client.model.Updates
        .combine(
            com.mongodb.client.model.Updates.set("operation", call.operation()),
            com.mongodb.client.model.Updates.set("aggregateId", call.workflowAggregateId()),
            com.mongodb.client.model.Updates.set("adapterId", call.adapterId()),
            com.mongodb.client.model.Updates
                .set("args", new Document(new java.util.LinkedHashMap<String, Object>(call.args()))),
            com.mongodb.client.model.Updates.set("createdAt", java.util.Date.from(now)),
            com.mongodb.client.model.Updates.set("nextAttemptAt", java.util.Date.from(now)));
    final var filter = com.mongodb.client.model.Filters
        .and(
            com.mongodb.client.model.Filters.eq("_id", waiting.getString("_id")),
            com.mongodb.client.model.Filters.eq("attempts", 0),
            com.mongodb.client.model.Filters
                .or(
                    com.mongodb.client.model.Filters.eq("leasedUntil", null),
                    com.mongodb.client.model.Filters.lte("leasedUntil", java.util.Date.from(now))));
    final var replaced = (session == null
        ? collection.updateOne(filter, replacement)
        : collection.updateOne(session, filter, replacement)).getModifiedCount() == 1;
    if (!replaced) {
      return false;
    }
    if (call.hasPayload()) {
      dispatcher.getPayloadStore().write(call);
    }
    final var replacedReference = waiting
        .get("args", Document.class)
        .getString(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
    if (replacedReference != null) {
      dispatcher.getPayloadStore().remove(replacedReference);
    }
    logReplacedEntry(call);
    return true;

  }

  /**
   * Dispatches right after the commit; on rollback the entry is deleted best-effort
   * where no MongoDB transaction covered the write (see the class javadoc for the
   * remaining crash windows).
   *
   * @param session The session covering the writes, or <code>null</code>
   * @param call The call which was scheduled
   * @param entryId The id of the entry which was written
   * @param replacedAnEntry Whether the call took the place of a waiting entry - there
   *        is no own document to delete on a rollback then, and undoing a replacement
   *        without a transaction is not something a store can promise
   */
  private void triggerPollAfterCommit(
      final com.mongodb.client.ClientSession session,
      final PhaseTwoCall call,
      final String entryId,
      final boolean replacedAnEntry) {

    if (txRegistry.getTransactionKey() == null) {
      dispatcher.triggerPoll();
      return;
    }
    final var collection = dispatcher.outboxCollection();
    txRegistry.registerInterposedSynchronization(new Synchronization() {
      @Override
      public void beforeCompletion() {
        // nothing to do
      }

      @Override
      public void afterCompletion(
          final int status) {
        if (status == Status.STATUS_COMMITTED) {
          dispatcher.triggerPoll();
        } else if ((session == null) && !replacedAnEntry) {
          // no MongoDB transaction covered the insert, so it has to be undone here;
          // with a session the abort of that transaction removed it already
          try {
            collection.deleteOne(new Document("_id", entryId));
            if (call.hasPayload()) {
              dispatcher.getPayloadStore().remove(call.payloadReference());
            }
          } catch (final RuntimeException e) {
            log.warn(
                "Could not delete the phase-two outbox entry '{}' after the rollback of the local "
                    + "transaction - the entry is an orphan and will end up BLOCKED after failing "
                    + "dispatches; clean it up manually",
                entryId,
                e);
          }
        }
      }
    });

  }

  /**
   * A younger call took the place of the entry which was waiting. At DEBUG for the
   * reason a discard is: it is what the caller asked for, and under a backlog it
   * happens as often as reports are planned.
   */
  private static void logReplacedEntry(
      final PhaseTwoCall call) {

    log.debug(
        "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' replaced the "
            + "entry which was waiting for its dispatch",
        call.operation(),
        call.bpmnProcessId(),
        call.workflowModuleId(),
        call.workflowAggregateId());

  }

  /**
   * A younger call could not take the place of the entry it meant to replace, because a
   * dispatch had claimed it. It becomes an entry of its own, so the handler is called
   * twice - worth a line, because an application counting its reports finds the second
   * one here.
   */
  private static void logSecondEntryBesideAClaimedOne(
      final PhaseTwoCall call) {

    log.debug(
        "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' asked to "
            + "replace an entry a dispatch had already taken - that one runs to its end and this "
            + "call becomes an entry of its own",
        call.operation(),
        call.bpmnProcessId(),
        call.workflowModuleId(),
        call.workflowAggregateId());

  }

  /**
   * The technical half of a discarded schedule. Which of the two causes it was - a
   * redelivered dispatch or an operation lost against one still waiting - the store
   * cannot tell, so the core reports it to the caller and this line stays at DEBUG.
   */
  private static void logDiscardedSchedule(
      final PhaseTwoCall call) {

    log.debug(
        "Phase two ({}) of BPMN process '{}' of workflow module '{}' for aggregate '{}' is still "
            + "waiting for its dispatch - the schedule of an identical operation was discarded",
        call.operation(),
        call.bpmnProcessId(),
        call.workflowModuleId(),
        call.workflowAggregateId());

  }

}
