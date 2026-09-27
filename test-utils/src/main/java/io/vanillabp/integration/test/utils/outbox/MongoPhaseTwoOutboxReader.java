package io.vanillabp.integration.test.utils.outbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.bson.Document;
import org.bson.types.Binary;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;

/**
 * What a test wants to know about the phase-two outbox of an application on MongoDB,
 * read from the database that application writes.
 * <p>
 * The counterpart of {@link PhaseTwoOutboxReader} and the same promise: a test asks for
 * entries, their state and their attempts, and it never writes the name of a collection
 * or of a field down. The names come from the platform classes which declare them (see
 * {@link MongoPhaseTwoOutboxNames}), so a rename is followed in one file instead of in
 * every test which reads a document.
 * <p>
 * Both platforms are served, because both write the same documents. A Spring Boot test
 * builds the reader over <code>mongoTemplate.getDb()</code>, a Quarkus test over
 * <code>mongoClient.getDatabase(...)</code>, and what either of them sees afterwards is
 * what that database hands out.
 * <p>
 * The reader reads and it writes what an operator would write, and it does neither
 * inside a session. A test which needs a read inside its own transaction asks the
 * template or the client it runs that transaction on, and a test which wants to know
 * what is committed builds the reader over a client of its own.
 */
public final class MongoPhaseTwoOutboxReader {

  /**
   * Where an entry stands, in the one spelling a test reads it in.
   */
  public enum State {

    /**
     * The entry is waiting for its dispatch, or for the next attempt of it.
     */
    WAITING,

    /**
     * The entry was dispatched. It stays in the collection until the retention passes.
     */
    DISPATCHED,

    /**
     * The entry was put aside after too many failed attempts, or after one attempt whose
     * failure would not be fixed by repeating it. Nothing attempts it again, a person has
     * to.
     */
    BLOCKED

  }

  /**
   * One entry of the outbox, with what a test asks about it.
   *
   * @param id The entry's own id, which is the document's <code>_id</code>
   * @param state Where the entry stands
   * @param attempts How often a dispatch took the entry, counted when the dispatch claims
   *          it, so an entry being dispatched right now already carries one
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param operation What the call does, as {@code PhaseOperation} names it
   * @param aggregateId The workflow aggregate the call belongs to, <code>null</code> for
   *          a call which names none, such as a broadcast signal
   * @param adapterId The adapter the call was elected for
   * @param idempotencyKey The key the entry is deduplicated by, <code>null</code> for an
   *          operation which is never deduplicated
   * @param dedupKey What the unique index spans. It carries the idempotency key while the
   *          entry waits and the entry's own id once it was dispatched, which is how a
   *          dispatched entry frees the key it held
   * @param leasedBy The node which claimed the entry, <code>null</code> where nobody
   *          holds it
   * @param nextAttemptAt When the entry asks to be dispatched
   * @param args What the call carries, the payload reference among it
   */
  public record Entry(
                      String id,
                      State state,
                      int attempts,
                      String workflowModuleId,
                      String bpmnProcessId,
                      String operation,
                      String aggregateId,
                      String adapterId,
                      String idempotencyKey,
                      String dedupKey,
                      String leasedBy,
                      Instant nextAttemptAt,
                      Map<String, String> args) {

    /**
     * Tells whether the entry still has its dispatch before it.
     *
     * @return Whether the entry is waiting for its dispatch
     */
    public boolean isWaiting() {

      return state == State.WAITING;

    }

    /**
     * Tells whether the outbox gave up on the entry.
     *
     * @return Whether the entry was put aside and waits for a person
     */
    public boolean isBlocked() {

      return state == State.BLOCKED;

    }

    /**
     * Tells whether the entry reached the BPMS.
     *
     * @return Whether the entry was dispatched
     */
    public boolean wasDispatched() {

      return state == State.DISPATCHED;

    }

  }

  /**
   * One payload document, which is where the bytes of a call lie while its entry waits.
   *
   * @param reference What the entry names the payload by, which is the document's
   *          <code>_id</code>
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param operation What the call does, as {@code PhaseOperation} names it
   * @param payload The bytes themselves
   */
  public record Payload(
                        String reference,
                        String workflowModuleId,
                        String bpmnProcessId,
                        String operation,
                        byte[] payload) {

  }

  private final MongoDatabase database;

  private final String outboxCollection;

  private final String payloadCollection;

  private final String waiting;

  private final String dispatched;

  private final String blocked;

  private MongoPhaseTwoOutboxReader(
      final MongoDatabase database,
      final String outboxCollection,
      final String payloadCollection) {

    this.database = database;
    this.outboxCollection = outboxCollection;
    this.payloadCollection = payloadCollection;
    this.waiting = MongoPhaseTwoOutboxNames.waitingStatus();
    this.dispatched = MongoPhaseTwoOutboxNames.dispatchedStatus();
    this.blocked = MongoPhaseTwoOutboxNames.blockedStatus();

  }

  /**
   * The reader for an application which left the collections their names.
   *
   * @param database The database of the application under test
   * @return The reader
   */
  public static MongoPhaseTwoOutboxReader ofTheVanillaBpOutbox(
      final MongoDatabase database) {

    final var outbox = MongoPhaseTwoOutboxNames.outboxCollection();
    return new MongoPhaseTwoOutboxReader(database, outbox, MongoPhaseTwoOutboxNames.payloadCollectionOf(outbox));

  }

  /**
   * The reader for an application which gave the outbox a name of its own
   * (<code>vanillabp.outbox.mongo.collection</code>). The payloads follow that name
   * unless the application named them too.
   *
   * @param database The database of the application under test
   * @param outboxCollection The collection the application configured for its entries
   * @return The reader
   */
  public static MongoPhaseTwoOutboxReader ofTheVanillaBpOutbox(
      final MongoDatabase database,
      final String outboxCollection) {

    return new MongoPhaseTwoOutboxReader(
        database, outboxCollection, MongoPhaseTwoOutboxNames.payloadCollectionOf(outboxCollection));

  }

  /**
   * The reader for an application which named both collections itself
   * (<code>vanillabp.outbox.mongo.collection</code> and
   * <code>vanillabp.outbox.mongo.payload-collection</code>).
   *
   * @param database The database of the application under test
   * @param outboxCollection The collection the application configured for its entries
   * @param payloadCollection The collection the application configured for the payloads
   * @return The reader
   */
  public static MongoPhaseTwoOutboxReader of(
      final MongoDatabase database,
      final String outboxCollection,
      final String payloadCollection) {

    return new MongoPhaseTwoOutboxReader(database, outboxCollection, payloadCollection);

  }

  /**
   * The name of the outbox collection, for the few tests which need the name itself
   * rather than what is in it: one counts the commands a driver sent to that collection,
   * another empties it beside collections of its own.
   *
   * @return The collection the entries lie in
   */
  public String outboxCollectionName() {

    return outboxCollection;

  }

  /**
   * The name of the payload collection, for the same reason.
   *
   * @return The collection the payloads lie in
   */
  public String payloadCollectionName() {

    return payloadCollection;

  }

  /**
   * The name of the outbox collection of an application which configured none, for a
   * test which needs the name before it has a database to read.
   *
   * @return The default collection name
   */
  public static String defaultOutboxCollectionName() {

    return MongoPhaseTwoOutboxNames.outboxCollection();

  }

  /**
   * The name of the payload collection of such an application.
   *
   * @return The default payload collection name
   */
  public static String defaultPayloadCollectionName() {

    return MongoPhaseTwoOutboxNames.payloadCollectionOf(MongoPhaseTwoOutboxNames.outboxCollection());

  }

  private MongoCollection<Document> entryDocuments() {

    return database.getCollection(outboxCollection);

  }

  private MongoCollection<Document> payloadDocuments() {

    return database.getCollection(payloadCollection);

  }

  /**
   * Every entry the outbox holds, waiting, dispatched and blocked alike. A dispatched
   * entry is one of them because it stays until the retention passes.
   *
   * @return The entries, in no particular order
   */
  public List<Entry> entries() {

    return read(new Document());

  }

  /**
   * The entries of one workflow aggregate.
   *
   * @param aggregateId The aggregate asked about
   * @return Its entries, in no particular order
   */
  public List<Entry> entriesOfAggregate(
      final String aggregateId) {

    return read(new Document("aggregateId", aggregateId));

  }

  /**
   * The entries of one BPMN process.
   *
   * @param bpmnProcessId The process asked about
   * @return Its entries, in no particular order
   */
  public List<Entry> entriesOf(
      final String bpmnProcessId) {

    return read(new Document("bpmnProcessId", bpmnProcessId));

  }

  /**
   * The entry of one idempotency key, which is the one entry an operation may have while
   * it waits.
   *
   * @param idempotencyKey The key asked about
   * @return The entry, or nothing where no entry carries that key any more
   */
  public Optional<Entry> entryOf(
      final String idempotencyKey) {

    return read(new Document("dedupKey", idempotencyKey))
        .stream()
        .findFirst();

  }

  /**
   * The entry of one id, which is what a test holds on to after it wrote an entry
   * itself.
   *
   * @param id The entry's own id
   * @return The entry, or nothing where it is gone
   */
  public Optional<Entry> entryById(
      final String id) {

    return read(new Document("_id", id))
        .stream()
        .findFirst();

  }

  /**
   * The entries which were dispatched, for a test which wants to read one of them rather
   * than count them.
   *
   * @return The dispatched entries, in no particular order
   */
  public List<Entry> entriesDispatchedAlready() {

    return read(new Document("status", dispatched));

  }

  /**
   * Puts a lease on an entry, which is what a node holding it leaves in the document. A
   * lease which ran out is what a node that died leaves behind.
   *
   * @param id The entry's own id
   * @param leasedBy The node holding it
   * @param leasedUntil How long that hold lasts
   */
  public void leaseEntry(
      final String id,
      final String leasedBy,
      final Instant leasedUntil) {

    entryDocuments()
        .updateOne(
            Filters.eq("_id", id),
            Updates
                .combine(
                    Updates.set("leasedBy", leasedBy),
                    Updates
                        .set(
                            "leasedUntil", leasedUntil == null
                                ? null
                                : Date.from(leasedUntil))));

  }

  /**
   * Does to an entry what the dispatch of ANOTHER node does to it: it claims the entry,
   * which is what takes the lease away from the node still working on it, and then writes
   * down that its own attempt got through.
   *
   * @param id The entry which changes hands
   * @param node The node taking it
   * @throws IllegalStateException If no entry of that id is there to change hands
   */
  public void anotherNodeTakesTheEntryAndDispatchesIt(
      final String id,
      final String node) {

    final var claimed = entryDocuments()
        .updateOne(
            Filters.eq("_id", id),
            Updates
                .combine(
                    Updates.set("leasedBy", node),
                    Updates.set("leasedUntil", Date.from(Instant.now().plus(java.time.Duration.ofHours(1))))));
    if (claimed.getMatchedCount() != 1) {
      throw new IllegalStateException(
          "The entry '%s' which was to change hands is not in '%s'!".formatted(id, outboxCollection));
    }
    entryDocuments()
        .updateOne(
            Filters.and(Filters.eq("_id", id), Filters.eq("leasedBy", node)),
            Updates
                .combine(
                    Updates.set("status", dispatched),
                    Updates.set("doneAt", Date.from(Instant.now())),
                    Updates.set("dedupKey", id),
                    Updates.inc("attempts", 1),
                    Updates.unset("leasedBy"),
                    Updates.unset("leasedUntil")));

  }

  /**
   * The question the poll of ANOTHER node asks: is there an entry which is due and which
   * nobody holds? It is a read and not the claim itself, so asking changes nothing for
   * the dispatcher under test.
   *
   * @return Whether another node would take an entry right now
   */
  public boolean somethingIsDueAndUnleased() {

    final var now = Date.from(Instant.now());
    return entryDocuments()
        .find(
            Filters
                .and(
                    Filters.eq("status", waiting),
                    Filters.lte("nextAttemptAt", now),
                    Filters.or(Filters.eq("leasedUntil", null), Filters.lte("leasedUntil", now))))
        .first() != null;

  }

  /**
   * How many entries wait for their dispatch. A blocked entry is not one of them: it
   * waits for a person rather than for the next attempt.
   *
   * @return The number of waiting entries
   */
  public long entriesWaiting() {

    return entryDocuments().countDocuments(new Document("status", waiting));

  }

  /**
   * How many entries were dispatched and are waiting for the retention to remove them.
   *
   * @return The number of dispatched entries
   */
  public long entriesDispatched() {

    return entryDocuments().countDocuments(new Document("status", dispatched));

  }

  /**
   * How many entries were put aside for a person.
   *
   * @return The number of blocked entries
   */
  public long entriesBlocked() {

    return entryDocuments().countDocuments(new Document("status", blocked));

  }

  /**
   * How many entries the collection holds at all.
   *
   * @return The number of entries
   */
  public long entriesAtAll() {

    return entryDocuments().countDocuments();

  }

  /**
   * The payloads waiting beside the entries.
   *
   * @return The payloads, in no particular order
   */
  public List<Payload> payloads() {

    final var payloads = new ArrayList<Payload>();
    payloadDocuments()
        .find()
        .forEach(document -> payloads.add(payloadOf(document)));
    return payloads;

  }

  /**
   * The payload an entry names.
   *
   * @param reference What the entry names it by
   * @return The payload, or nothing where it was removed already
   */
  public Optional<Payload> payloadOf(
      final String reference) {

    return Optional
        .ofNullable(payloadDocuments().find(new Document("_id", reference)).first())
        .map(MongoPhaseTwoOutboxReader::payloadOf);

  }

  /**
   * How many payloads lie beside the entries.
   *
   * @return The number of payloads
   */
  public long payloadsAtAll() {

    return payloadDocuments().countDocuments();

  }

  /**
   * Makes the entry of one idempotency key due, which is how a test brings back an entry
   * a dispatch pushed into the future.
   *
   * @param idempotencyKey The key of the entry
   */
  public void makeDueNow(
      final String idempotencyKey) {

    entryDocuments()
        .updateMany(
            Filters.eq("dedupKey", idempotencyKey),
            Updates.set("nextAttemptAt", Date.from(Instant.now())));

  }

  /**
   * Puts every entry back into the state an operator leaves a repaired one in: waiting,
   * without attempts, without a lease and due a minute ago. It is also what an entry
   * another node wrote looks like from here, which is a document nothing told this node
   * about.
   */
  public void openEveryEntryAgain() {

    entryDocuments()
        .updateMany(
            new Document(),
            Updates
                .combine(
                    Updates.set("status", waiting),
                    Updates.set("attempts", 0),
                    Updates.set("nextAttemptAt", Date.from(Instant.now().minusSeconds(60))),
                    Updates.unset("leasedBy"),
                    Updates.unset("leasedUntil"),
                    Updates.unset("doneAt")));

  }

  /**
   * Writes an entry which stays where it is: it waits, and the moment of its next attempt
   * is the caller's to choose, so a test can put one an hour ahead and read what a store
   * says about an entry nobody dispatches.
   *
   * @param id The entry's own id, which is also the key it deduplicates by
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param operation What the call does, as {@code PhaseOperation} names it
   * @param aggregateId The workflow aggregate the call belongs to
   * @param adapterId The adapter the call is meant for
   * @param writtenAt The moment the entry counts as written, which is what its age is
   *          measured from
   * @param dueAt The moment the entry asks to be dispatched at
   */
  public void writeWaitingEntry(
      final String id,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String operation,
      final String aggregateId,
      final String adapterId,
      final Instant writtenAt,
      final Instant dueAt) {

    writeWaitingEntry(id, workflowModuleId, bpmnProcessId, operation, aggregateId, adapterId, id, writtenAt, dueAt);

  }

  /**
   * The same, for a test whose entry has to carry the arguments a real call would have
   * handed over.
   *
   * @param id The entry's own id, which is also the key it deduplicates by
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param operation What the call does, as {@code PhaseOperation} names it
   * @param aggregateId The workflow aggregate the call belongs to
   * @param adapterId The adapter the call is meant for
   * @param args What the dispatch is handed
   * @param writtenAt The moment the entry counts as written
   * @param dueAt The moment the entry asks to be dispatched at
   */
  public void writeWaitingEntry(
      final String id,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String operation,
      final String aggregateId,
      final String adapterId,
      final Map<String, String> args,
      final Instant writtenAt,
      final Instant dueAt) {

    writeWaitingEntry(
        id, workflowModuleId, bpmnProcessId, operation, aggregateId, adapterId, id, args, writtenAt, dueAt);

  }

  /**
   * The same, for a test which needs the entry to carry the key a real call would have
   * derived rather than its own id.
   *
   * @param id The entry's own id
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param operation What the call does, as {@code PhaseOperation} names it
   * @param aggregateId The workflow aggregate the call belongs to
   * @param adapterId The adapter the call is meant for
   * @param idempotencyKey The key the entry deduplicates by while it waits
   * @param writtenAt The moment the entry counts as written
   * @param dueAt The moment the entry asks to be dispatched at
   */
  public void writeWaitingEntry(
      final String id,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String operation,
      final String aggregateId,
      final String adapterId,
      final String idempotencyKey,
      final Instant writtenAt,
      final Instant dueAt) {

    writeWaitingEntry(
        id, workflowModuleId, bpmnProcessId, operation, aggregateId, adapterId, idempotencyKey, Map.of(), writtenAt,
        dueAt);

  }

  /**
   * The widest of them, which the others fill in for.
   *
   * @param id The entry's own id
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param operation What the call does, as {@code PhaseOperation} names it
   * @param aggregateId The workflow aggregate the call belongs to
   * @param adapterId The adapter the call is meant for
   * @param idempotencyKey The key the entry deduplicates by while it waits
   * @param args What the dispatch is handed
   * @param writtenAt The moment the entry counts as written
   * @param dueAt The moment the entry asks to be dispatched at
   */
  public void writeWaitingEntry(
      final String id,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String operation,
      final String aggregateId,
      final String adapterId,
      final String idempotencyKey,
      final Map<String, String> args,
      final Instant writtenAt,
      final Instant dueAt) {

    final var arguments = new Document();
    args.forEach(arguments::append);
    entryDocuments()
        .insertOne(
            new Document("_id", id)
                .append("workflowModuleId", workflowModuleId)
                .append("bpmnProcessId", bpmnProcessId)
                .append("operation", operation)
                .append("aggregateId", aggregateId)
                .append("adapterId", adapterId)
                .append("args", arguments)
                .append("idempotencyKey", idempotencyKey)
                .append("dedupKey", idempotencyKey)
                .append("status", waiting)
                .append("createdAt", Date.from(writtenAt))
                .append("attempts", 0)
                .append("nextAttemptAt", Date.from(dueAt)));

  }

  /**
   * Writes an entry which was dispatched already and is waiting for the retention to
   * remove it. What a test needs it for is the key such an entry holds: a dispatched
   * entry carries its own id as the key the unique index spans, so an entry whose id is
   * that key cannot be marked.
   *
   * @param id The entry's own id
   * @param workflowModuleId The workflow module the call belonged to
   * @param bpmnProcessId The BPMN process the call belonged to
   * @param operation What the call did, as {@code PhaseOperation} names it
   * @param aggregateId The workflow aggregate the call belonged to
   * @param adapterId The adapter the call went to
   * @param dedupKey What this entry holds of the unique index
   * @param dispatchedAt The moment it was dispatched
   */
  public void writeDispatchedEntry(
      final String id,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String operation,
      final String aggregateId,
      final String adapterId,
      final String dedupKey,
      final Instant dispatchedAt) {

    writeDispatchedEntry(
        id, workflowModuleId, bpmnProcessId, operation, aggregateId, adapterId, dedupKey, Map.of(), dispatchedAt);

  }

  /**
   * The same, for an entry which carried arguments, the reference of a payload among
   * them.
   *
   * @param id The entry's own id
   * @param workflowModuleId The workflow module the call belonged to
   * @param bpmnProcessId The BPMN process the call belonged to
   * @param operation What the call did, as {@code PhaseOperation} names it
   * @param aggregateId The workflow aggregate the call belonged to
   * @param adapterId The adapter the call went to
   * @param dedupKey What this entry holds of the unique index
   * @param args What the dispatch was handed
   * @param dispatchedAt The moment it was dispatched
   */
  public void writeDispatchedEntry(
      final String id,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String operation,
      final String aggregateId,
      final String adapterId,
      final String dedupKey,
      final Map<String, String> args,
      final Instant dispatchedAt) {

    entryDocuments()
        .insertOne(
            entryDocument(id, workflowModuleId, bpmnProcessId, operation, aggregateId, adapterId, args)
                .append("dedupKey", dedupKey)
                .append("status", dispatched)
                .append("createdAt", Date.from(dispatchedAt))
                .append("nextAttemptAt", Date.from(dispatchedAt))
                .append("doneAt", Date.from(dispatchedAt))
                .append("attempts", 1));

  }

  /**
   * Writes an entry the store put aside for a person. Nothing attempts it again, and no
   * retention removes it, which is what a test about the housekeeping needs one for.
   *
   * @param id The entry's own id, which is also the key a blocked entry holds
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param operation What the call does, as {@code PhaseOperation} names it
   * @param aggregateId The workflow aggregate the call belongs to
   * @param adapterId The adapter the call was meant for
   * @param args What the dispatch would be handed
   * @param writtenAt The moment the entry counts as written
   */
  public void writeBlockedEntry(
      final String id,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String operation,
      final String aggregateId,
      final String adapterId,
      final Map<String, String> args,
      final Instant writtenAt) {

    entryDocuments()
        .insertOne(
            entryDocument(id, workflowModuleId, bpmnProcessId, operation, aggregateId, adapterId, args)
                // the key of a blocked entry is released the way a dispatched one
                // releases it, so both carry their own id here
                .append("dedupKey", id)
                .append("status", blocked)
                .append("createdAt", Date.from(writtenAt))
                .append("nextAttemptAt", Date.from(writtenAt))
                .append("attempts", 0));

  }

  /**
   * Writes the bytes of a call the way the store writes them while its entry waits.
   *
   * @param reference What the entry names the payload by
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param operation What the call does, as {@code PhaseOperation} names it
   * @param payload The bytes themselves
   * @param writtenAt The moment the payload counts as written, which is what its age is
   *          measured from
   */
  public void writePayload(
      final String reference,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String operation,
      final byte[] payload,
      final Instant writtenAt) {

    payloadDocuments()
        .insertOne(
            new Document("_id", reference)
                .append("workflowModuleId", workflowModuleId)
                .append("bpmnProcessId", bpmnProcessId)
                .append("operation", operation)
                .append("payload", new Binary(payload))
                .append("createdAt", Date.from(writtenAt)));

  }

  /**
   * What every entry this class writes has in common.
   *
   * @param id The entry's own id
   * @param workflowModuleId The workflow module the call belongs to
   * @param bpmnProcessId The BPMN process the call belongs to
   * @param operation What the call does, as {@code PhaseOperation} names it
   * @param aggregateId The workflow aggregate the call belongs to
   * @param adapterId The adapter the call is meant for
   * @param args What the dispatch is handed
   * @return The document, without what the state of the entry adds to it
   */
  private static Document entryDocument(
      final String id,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String operation,
      final String aggregateId,
      final String adapterId,
      final Map<String, String> args) {

    final var arguments = new Document();
    args.forEach(arguments::append);
    return new Document("_id", id)
        .append("workflowModuleId", workflowModuleId)
        .append("bpmnProcessId", bpmnProcessId)
        .append("operation", operation)
        .append("aggregateId", aggregateId)
        .append("adapterId", adapterId)
        .append("args", arguments);

  }

  /**
   * Removes every entry and every payload, which is what a test leaves behind for the
   * next one.
   */
  public void removeAllEntries() {

    entryDocuments().deleteMany(new Document());
    payloadDocuments().deleteMany(new Document());

  }

  /**
   * The keys of the indexes over the outbox collection, which is what a test asks when
   * the question is whether a read can use one.
   *
   * @return One document of keys per index
   */
  public List<Document> indexKeys() {

    final var keys = new ArrayList<Document>();
    entryDocuments()
        .listIndexes()
        .forEach(index -> keys.add(index.get("key", Document.class)));
    return keys;

  }

  private List<Entry> read(
      final Document filter) {

    final var entries = new ArrayList<Entry>();
    entryDocuments()
        .find(filter)
        .forEach(document -> entries.add(entryOf(document)));
    return entries;

  }

  private Entry entryOf(
      final Document document) {

    final var status = document.getString("status");
    final State state;
    if (dispatched.equals(status)) {
      state = State.DISPATCHED;
    } else if (blocked.equals(status)) {
      state = State.BLOCKED;
    } else {
      state = State.WAITING;
    }
    final var args = new LinkedHashMap<String, String>();
    final var written = document.get("args", Document.class);
    if (written != null) {
      written.forEach((
          key,
          value) -> args.put(key, value == null
              ? null
              : value.toString()));
    }
    final var dueAt = document.getDate("nextAttemptAt");
    return new Entry(
        document.getString("_id"), state, document.getInteger("attempts", 0), document.getString(
            "workflowModuleId"), document.getString("bpmnProcessId"), document.getString("operation"), document
                .getString("aggregateId"), document.getString("adapterId"), document.getString(
                    "idempotencyKey"), document.getString("dedupKey"), document.getString("leasedBy"), dueAt == null
                        ? null
                        : dueAt.toInstant(), args);

  }

  private static Payload payloadOf(
      final Document document) {

    final var bytes = document.get("payload", Binary.class);
    return new Payload(
        document.getString("_id"), document.getString("workflowModuleId"), document.getString("bpmnProcessId"), document
            .getString("operation"), bytes == null
                ? null
                : bytes.getData());

  }

}
