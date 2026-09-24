package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;
import java.util.List;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoPayloadStore;
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
 * which is what {@link #removeOrphansOlderThan(Instant, EntriesNamingPayloads)} is
 * for.
 */
@Slf4j
public class MongoPhaseTwoPayloadStore implements PhaseTwoPayloadStore {

  private final MongoTemplate mongoTemplate;

  /**
   * The collection the payload documents live in - one per outbox, for the reason the
   * outbox itself has one.
   */
  private final String collection;

  /**
   * Built by the dispatcher of the outbox this store belongs to, which is what makes the
   * two agree on the collection.
   *
   * @param mongoTemplate The template the payloads are written and read through - the same
   *          one the entries use, so a payload becomes visible exactly when its entry does
   * @param collection The collection the payload documents go into
   */
  public MongoPhaseTwoPayloadStore(
      final MongoTemplate mongoTemplate,
      final String collection) {

    this.mongoTemplate = mongoTemplate;
    this.collection = collection;

  }

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
      final var removed = (int) mongoTemplate
          .remove(Query.query(Criteria.where("_id").in(orphans)), collection)
          .getDeletedCount();
      logRemovedOrphans(removed);
      return removed;
    } catch (final RuntimeException e) {
      log.warn("Could not remove the orphaned payloads", e);
      return 0;
    }

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
            "Removed {} payload(s) from collection '{}' which no outbox entry names any more",
            removed,
            collection);

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

    final var query = Query.query(Criteria.where("createdAt").lt(threshold));
    query.fields().include("_id");
    return mongoTemplate
        .find(query, PhaseTwoPayloadDocument.class, collection)
        .stream()
        .map(PhaseTwoPayloadDocument::getId)
        .toList();

  }

}
