package io.vanillabp.integration.runtime.outbox;

import java.time.Instant;
import java.util.Date;
import java.util.function.Supplier;

import org.bson.Document;
import org.bson.types.Binary;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;

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
 * behind, which is what {@link #removeOlderThan(Instant)} is for.
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
  public int removeOlderThan(
      final Instant threshold) {

    try {
      return (int) collection
          .get()
          .deleteMany(Filters.lt("createdAt", Date.from(threshold)))
          .getDeletedCount();
    } catch (final RuntimeException e) {
      log.warn("Could not remove the expired payloads", e);
      return 0;
    }

  }

}
