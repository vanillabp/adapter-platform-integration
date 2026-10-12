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
 * The payload store of the MongoDB outbox on Quarkus. It keeps one document per phase-two
 * call which has a payload, in a collection of its own
 * (<code>vanillabp.outbox.mongo.payload-collection</code>).
 * <p>
 * The bytes are a field of that document, not a file in GridFS. So a payload can be at most
 * as big as one MongoDB document, 16 MB. VanillaBP limits it to far less than that
 * ({@link PhaseTwoCall#MAX_PAYLOAD_SIZE}). So the same payload can be stored whatever store
 * an application runs.
 * <p>
 * The document is written through the session of the running transaction where MongoDB
 * Panache provides one, exactly like the outbox entry. So the two commit together. Without
 * such a session, the write happens at once, and a rollback can leave a document behind.
 * That is what {@link #removeOrphansOlderThan(Instant, int)} is for.
 */
@Slf4j
public class MongoPhaseTwoPayloadStore implements PhaseTwoPayloadStore {

  /**
   * The field into which the lookup writes the entries that name a payload. It exists only
   * while the pipeline runs and is never stored. It is spelled once, because the stage which
   * fills it and the stage which reads it have to agree.
   */
  private static final String NAMING_ENTRIES = "namingEntries";

  private final Supplier<MongoCollection<Document>> collection;

  /**
   * The collection which the outbox entries lie in. The housekeeping joins it. So the database
   * answers which payloads are still named. No set of references has to travel through the
   * application. It is a supplier for the same reason as the payload collection: the name is
   * read lazily.
   */
  private final Supplier<String> entryCollection;

  private final TransactionSynchronizationRegistry txRegistry;

  /**
   * The dispatcher of the MongoDB outbox creates it and resolves the collection from the
   * configuration. The collection arrives as a supplier, because its name is read lazily.
   * The outbox reads its own name the same way.
   *
   * @param collection Where the payload documents live
   * @param entryCollection The name of the collection which the outbox entries lie in. The
   *          housekeeping joins it
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
      // The entry it belonged to was dispatched, and that is what counts. The
      // housekeeping removes the document instead, one retention period later.
      log.warn("Could not remove the payload '{}'", reference, e);
    }

  }

  /**
   * {@inheritDoc}
   * <p>
   * The entries are joined in the database. The pipeline reads the payloads which are old
   * enough. It looks each of them up among the entries and keeps the ones for which nothing
   * was found. Only their ids come back, at most as many as asked for. They go into one
   * <code>deleteMany</code>. The lookup reads the sparse index over the reference inside the
   * arguments of an entry. That makes it a lookup and not a scan (see decision 76 in the
   * repository's DECISIONS.md).
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
   * The payloads which are old enough to go and which no entry names any more. They are read
   * in one pipeline.
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
   * payload whose outbox entry never reached the collection. So nothing was lost by removing
   * it, and the normal count is zero. Somebody who sees the payloads grow wants to see this
   * line, and nobody else does.
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
