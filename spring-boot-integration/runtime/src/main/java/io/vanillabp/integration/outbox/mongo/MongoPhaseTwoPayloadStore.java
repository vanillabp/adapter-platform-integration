package io.vanillabp.integration.outbox.mongo;

import java.time.Instant;
import java.util.List;

import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
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
 * which is what {@link #removeOrphansOlderThan(Instant, int)} is for.
 */
@Slf4j
public class MongoPhaseTwoPayloadStore implements PhaseTwoPayloadStore {

  /**
   * The field the lookup writes the entries naming a payload into. It exists for the
   * length of the pipeline and is never stored, and it is spelled once because the stage
   * which fills it and the stage which reads it have to agree.
   */
  private static final String NAMING_ENTRIES = "namingEntries";

  private final MongoTemplate mongoTemplate;

  /**
   * The collection the payload documents live in - one per outbox, for the reason the
   * outbox itself has one.
   */
  private final String collection;

  /**
   * The collection the outbox entries live in. The housekeeping joins it, so the question
   * which payloads are still named is answered in the database rather than by a set of
   * references travelling through the application.
   */
  private final String entryCollection;

  /**
   * Built by the dispatcher of the outbox this store belongs to, which is what makes the
   * two agree on the collection.
   *
   * @param mongoTemplate The template the payloads are written and read through - the same
   *          one the entries use, so a payload becomes visible exactly when its entry does
   * @param collection The collection the payload documents go into
   * @param entryCollection The collection the outbox entries lie in, joined by the
   *          housekeeping
   */
  public MongoPhaseTwoPayloadStore(
      final MongoTemplate mongoTemplate,
      final String collection,
      final String entryCollection) {

    this.mongoTemplate = mongoTemplate;
    this.collection = collection;
    this.entryCollection = entryCollection;

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
    final var pipeline = Aggregation
        .newAggregation(
            Aggregation.match(Criteria.where("createdAt").lt(threshold)),
            Aggregation
                .lookup()
                .from(entryCollection)
                .localField("_id")
                .foreignField(namedBy)
                .as(NAMING_ENTRIES),
            Aggregation.match(Criteria.where(NAMING_ENTRIES).size(0)),
            Aggregation.limit(maxEntries),
            Aggregation.project("_id"));
    return mongoTemplate
        .aggregate(pipeline, collection, Document.class)
        .getMappedResults()
        .stream()
        .map(document -> document.getString("_id"))
        .toList();

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

}
