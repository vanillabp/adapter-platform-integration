package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoPayloadStore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The payload store of the MongoDB-based outbox on Spring Boot: one document per
 * phase-two call which carries a payload, in a collection of its own
 * (<code>vanillabp.outbox.mongo.payload-collection</code>).
 * <p>
 * The bytes are a field of that document rather than a file in GridFS, which is what
 * bounds a payload at what MongoDB holds in one document, 16 MB. VanillaBP bounds it
 * far below that ({@link PhaseTwoCall#MAX_PAYLOAD_SIZE}), so the same payload is
 * storable whichever store an application runs.
 * <p>
 * The write goes through the same {@link MongoTemplate} the outbox entry goes through,
 * so it takes part in the running MongoDB transaction where the deployment is a replica
 * set. Without one the write is immediate and a rollback can leave a document behind,
 * which is what {@link #removeOlderThan(Instant)} is for.
 */
@RequiredArgsConstructor
@Slf4j
public class MongoPhaseTwoPayloadStore implements PhaseTwoPayloadStore {

  private final MongoTemplate mongoTemplate;

  /**
   * The collection the payload documents live in - one per outbox, for the reason the
   * outbox itself has one.
   */
  private final String collection;

  @Override
  public void write(
      final PhaseTwoCall call) {

    mongoTemplate
        .insert(
            new PhaseTwoPayloadDocument(
                call.payloadReference(), call.workflowModuleId(), call.bpmnProcessId(), call
                    .operation(), call.payload(), Instant.now()),
            collection);

  }

  @Override
  public byte[] read(
      final String reference) {

    final var document = mongoTemplate
        .findOne(
            Query.query(Criteria.where("_id").is(reference)), PhaseTwoPayloadDocument.class, collection);
    return document == null ? null : document.getPayload();

  }

  @Override
  public void remove(
      final String reference) {

    try {
      mongoTemplate.remove(Query.query(Criteria.where("_id").is(reference)), collection);
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
      return (int) mongoTemplate
          .remove(Query.query(Criteria.where("createdAt").lt(threshold)), collection)
          .getDeletedCount();
    } catch (final RuntimeException e) {
      log.warn("Could not remove the expired payloads", e);
      return 0;
    }

  }

}
