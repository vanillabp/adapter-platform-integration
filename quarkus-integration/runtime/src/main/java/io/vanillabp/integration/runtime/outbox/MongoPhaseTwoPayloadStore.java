package io.vanillabp.integration.runtime.outbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

import org.bson.Document;
import org.bson.types.Binary;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;

import io.vanillabp.integration.runtime.mongo.MongoSessions;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoPayloadStore;
import jakarta.transaction.TransactionSynchronizationRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * The payload store of the MongoDB-based outbox on Quarkus: one document per phase-two
 * call which carries a payload, in a collection of its own
 * (<code>vanillabp.outbox.mongo.payload-collection</code>).
 * <p>
 * The bytes are a field of that document rather than a file in GridFS, which is what
 * bounds a payload at what MongoDB holds in one document, 16 MB. VanillaBP bounds it
 * far below that ({@link PhaseTwoCall#MAX_PAYLOAD_SIZE}), so the same payload is
 * storable whichever store an application runs.
 * <p>
 * The document is written through the session of the running transaction where MongoDB
 * Panache provides one, exactly like the outbox entry, so the two commit together.
 * Without such a session the write is immediate and a rollback can leave a document
 * behind, which is what {@link #removeOrphansOlderThan(Instant, int)} is for.
 */
@Slf4j
public class MongoPhaseTwoPayloadStore implements PhaseTwoPayloadStore {

  /**
   * The field the lookup writes the entries naming a payload into. It exists for the
   * length of the pipeline and is never stored, and it is spelled once because the stage
   * which fills it and the stage which reads it have to agree.
   */
  private static final String NAMING_ENTRIES = "namingEntries";

  private final Supplier<MongoCollection<Document>> collection;

  /**
   * The collection the outbox entries lie in. The housekeeping joins it, so the question
   * which payloads are still named is answered in the database rather than by a set of
   * references travelling through the application. A supplier for the same reason the
   * payload collection is one: the name is read lazily.
   */
  private final Supplier<String> entryCollection;

  private final TransactionSynchronizationRegistry txRegistry;

  /**
   * Built by the dispatcher of the MongoDB outbox, which resolves the collection from
   * the configuration. The collection arrives as a supplier because its name is read
   * lazily, the same way the outbox reads its own.
   *
   * @param collection Where the payload documents live
   * @param entryCollection The name of the collection the outbox entries lie in, joined by
   *          the housekeeping
   * @param txRegistry Used to find the session of the running transaction
   */
  public MongoPhaseTwoPayloadStore(
      final Supplier<MongoCollection<Document>> collection,
      final Supplier<String> entryCollection,
      final TransactionSynchronizationRegistry txRegistry) {

    this.collection = collection;
    this.entryCollection = entryCollection;
    this.txRegistry = txRegistry;

  }

  @Override
  public void write(
      final PhaseTwoCall call) {

    final var document = new Document()
        .append("_id", call.payloadReference())
        .append("workflowModuleId", call.workflowModuleId())
        .append("bpmnProcessId", call.bpmnProcessId())
        .append("operation", call.operation())
        .append("payload", new Binary(call.payload()))
        .append("createdAt", Date.from(Instant.now()));
    final var session = MongoSessions.activeSession(txRegistry);
    if (session != null) {
      collection.get().insertOne(session, document);
    } else {
      collection.get().insertOne(document);
    }

  }

  @Override
  public byte[] read(
      final String reference) {

    final var document = collection
        .get()
        .find(Filters.eq("_id", reference))
        .first();
    if (document == null) {
      return null;
    }
    final var payload = document.get("payload", Binary.class);
    return payload == null ? null : payload.getData();

  }

  @Override
  public void remove(
      final String reference) {

    try {
      collection.get().deleteOne(Filters.eq("_id", reference));
    } catch (final RuntimeException e) {
      // the entry it belonged to was dispatched, which is what counts - the document is
      // removed by the housekeeping instead, one retention period later
      log.warn("Could not remove the payload '{}'", reference, e);
    }

  }

  /**
   * {@inheritDoc}
   * <p>
   * The entries are joined in the database: the pipeline reads the payloads which are old
   * enough, looks each of them up among the entries and keeps the ones nothing found. Only
   * the ids of those come back, at most as many as asked for, and they go into one
   * <code>deleteMany</code>. The lookup reads the sparse index over the reference inside an
   * entry's arguments, which is what makes it a lookup rather than a scan (see decision 76
   * in the repository's DECISIONS.md).
   */
  @Override
  public int removeOrphansOlderThan(
      final Instant threshold,
      final int maxEntries) {

    if (maxEntries < 1) {
      return 0;
    }
    try {
      final var orphans = orphanedReferences(threshold, maxEntries);
      if (orphans.isEmpty()) {
        return 0;
      }
      final var removed = (int) collection
          .get()
          .deleteMany(Filters.in("_id", orphans))
          .getDeletedCount();
      logRemovedOrphans(removed);
      return removed;
    } catch (final RuntimeException e) {
      log.warn("Could not remove the orphaned payloads", e);
      return 0;
    }

  }

  /**
   * The payloads which are old enough to go and which no entry names any more, read in one
   * pipeline.
   *
   * @param threshold Payloads written before this moment are candidates
   * @param maxEntries The most references to bring back
   * @return Their references
   */
  private List<String> orphanedReferences(
      final Instant threshold,
      final int maxEntries) {

    final var namedBy = "args.%s".formatted(PhaseTwoCall.ARG_PAYLOAD_REFERENCE);
    final var references = new ArrayList<String>();
    collection
        .get()
        .aggregate(
            List
                .of(
                    Aggregates.match(Filters.lt("createdAt", Date.from(threshold))),
                    Aggregates.lookup(entryCollection.get(), "_id", namedBy, NAMING_ENTRIES),
                    Aggregates.match(Filters.size(NAMING_ENTRIES, 0)),
                    Aggregates.limit(maxEntries),
                    Aggregates.project(Projections.include("_id"))))
        .forEach(document -> references.add(document.getString("_id")));
    return references;

  }

  /**
   * Says that bytes were thrown away, at DEBUG and only when there were any. An orphan is a
   * payload whose outbox entry never reached the collection, so nothing was lost by removing it,
   * and the normal count is zero. Somebody who finds payloads growing wants to see this line, and
   * nobody else does.
   *
   * @param removed How many payloads went
   */
  private void logRemovedOrphans(
      final int removed) {

    if (removed == 0) {
      return;
    }
    log
        .debug(
            "Removed {} payload(s) which no outbox entry names any more",
            removed);

  }

}
