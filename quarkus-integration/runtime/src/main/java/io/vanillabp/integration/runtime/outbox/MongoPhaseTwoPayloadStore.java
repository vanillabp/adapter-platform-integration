package io.vanillabp.integration.runtime.outbox;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.Supplier;

import org.bson.Document;
import org.bson.types.Binary;

import com.mongodb.client.MongoCollection;
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
 * behind, which is what {@link #removeOrphansOlderThan(Instant, EntriesNamingPayloads)}
 * is for.
 */
@Slf4j
public class MongoPhaseTwoPayloadStore implements PhaseTwoPayloadStore {

  private final Supplier<MongoCollection<Document>> collection;

  private final TransactionSynchronizationRegistry txRegistry;

  /**
   * @param collection Where the payload documents live
   * @param txRegistry Used to find the session of the running transaction
   */
  public MongoPhaseTwoPayloadStore(
      final Supplier<MongoCollection<Document>> collection,
      final TransactionSynchronizationRegistry txRegistry) {

    this.collection = collection;
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

  @Override
  public int removeOrphansOlderThan(
      final Instant threshold,
      final EntriesNamingPayloads entries) {

    try {
      final var expired = expiredReferences(threshold);
      if (expired.isEmpty()) {
        return 0;
      }
      final var stillNamed = entries.stillNaming(expired);
      final var orphans = expired
          .stream()
          .filter(reference -> !stillNamed.contains(reference))
          .toList();
      if (orphans.isEmpty()) {
        return 0;
      }
      return (int) collection
          .get()
          .deleteMany(Filters.in("_id", orphans))
          .getDeletedCount();
    } catch (final RuntimeException e) {
      log.warn("Could not remove the orphaned payloads", e);
      return 0;
    }

  }

  /**
   * The payloads which are old enough to go, read along the index over
   * <code>createdAt</code>. On a healthy store this reads nothing: a payload is removed
   * with the dispatch of its entry and with the deletion of that entry, so what stays
   * beyond the retention either belongs to an entry which waits or belongs to no entry
   * at all.
   *
   * @param threshold Payloads written before this moment
   * @return Their references
   */
  private List<String> expiredReferences(
      final Instant threshold) {

    final var references = new ArrayList<String>();
    collection
        .get()
        .find(Filters.lt("createdAt", Date.from(threshold)))
        .projection(Projections.include("_id"))
        .forEach(document -> references.add(document.getString("_id")));
    return references;

  }

}
