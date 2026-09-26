package io.vanillabp.integration.adapter.migration.mongo;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.spi.PhaseTwoCall;
import lombok.extern.slf4j.Slf4j;

/**
 * The indexes the MongoDB stores of VanillaBP work with, and what a startup says about the
 * ones which are missing. Both platform integrations create them from the lists below and
 * both hold an existing collection against the same lists, the way
 * {@link io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema} serves the relational
 * stores.
 * <p>
 * A collection needs no creating on MongoDB, it appears with the first document, so
 * <code>vanillabp.outbox.create-schema: false</code> leaves nothing to verify but the
 * indexes. That is why a missing one is reported and does not end the boot. Every question
 * is still answered, only it is read from the whole collection instead of from an index.
 * The relational stores report their missing indexes the same way and end the boot only
 * where a table or a column is missing, which on MongoDB has no counterpart.
 */
@Slf4j
public final class MongoSchema {

  private MongoSchema() {
  }

  /**
   * The indexes of the outbox collection
   * (<code>vanillabp.outbox.mongo.collection</code>).
   * <p>
   * Three of them filter the same status and then read a moment of its own, and that is
   * three indexes rather than one over three moments: such an index would serve none of
   * the three questions.
   */
  public static final List<MongoIndex> OUTBOX_INDEXES = List
      .of(
          // 'dedupKey' and not 'idempotencyKey': the key deduplicates the operations still
          // waiting for their dispatch, and the field holds the entry's own id once it was
          // dispatched. Present on every entry, so the index needs neither sparse nor a
          // partial filter
          MongoIndex.uniqueIndexReadBy("what keeps one operation from being planned twice", "dedupKey"),
          MongoIndex.readBy("which entry is due next, and the claim which picks it up", "status", "nextAttemptAt"),
          MongoIndex.readBy("what the retention may delete, and the delete itself", "status", "doneAt"),
          MongoIndex.readBy("how long the oldest waiting entry has waited", "status", "createdAt"),
          // only an entry which carries a payload has the field, and that is the rare one
          MongoIndex
              .sparseIndexReadBy(
                  "which of the expired payloads an entry still names",
                  "args.%s".formatted(PhaseTwoCall.ARG_PAYLOAD_REFERENCE)));

  /**
   * The index of the payload collection
   * (<code>vanillabp.outbox.mongo.payload-collection</code>). Without it the housekeeping
   * reads every payload ever written.
   */
  public static final List<MongoIndex> PAYLOAD_INDEXES = List
      .of(MongoIndex.readBy("which payloads the housekeeping deletes by age", "createdAt"));

  /**
   * The indexes of the delivery-log collection
   * (<code>vanillabp.outbox.mongo.delivery-collection</code>). MongoDB knows no key-length
   * limit, so the aggregate id itself is an index here, unlike in the relational table
   * whose column is too wide for one.
   */
  public static final List<MongoIndex> DELIVERY_INDEXES = List
      .of(
          MongoIndex.readBy("which records the retention deletes by age", "lastSeenAt"),
          MongoIndex.readBy("the record of the task an operation names", "taskId"),
          MongoIndex.readBy("the open tasks of one workflow aggregate", "aggregateId"),
          MongoIndex.readBy("the open tasks of one workflow of the BPMS", "workflowId"));

  /**
   * One index a collection carries today, read back from the database by the platform
   * integration.
   *
   * @param fields The fields it spans, in the order the database holds them
   * @param unique Whether it refuses a second document with those values
   */
  public record IndexInPlace(
                             List<String> fields,
                             boolean unique) {
  }

  /**
   * Which of the needed indexes no existing one does the work of.
   *
   * @param needed What the store reads by
   * @param indexesInPlace What the collection carries, empty where the collection does not
   *          exist yet
   * @return The missing indexes, in the order they are described above
   */
  public static List<MongoIndex> missingIndexes(
      final List<MongoIndex> needed,
      final Collection<IndexInPlace> indexesInPlace) {

    return needed
        .stream()
        .filter(index -> indexesInPlace
            .stream()
            .noneMatch(index::isServedBy))
        .toList();

  }

  /**
   * Warns about every index a collection is missing, naming the statement which creates
   * it. Called by an application which manages its schema itself
   * (<code>vanillabp.outbox.create-schema: false</code>), because nothing else tells such
   * an application what MongoDB expects of it.
   *
   * @param collection The collection as the application named it
   * @param needed What the store reads by
   * @param indexesInPlace What the collection carries, empty where the collection does not
   *          exist yet
   */
  public static void reportMissingIndexes(
      final String collection,
      final List<MongoIndex> needed,
      final Collection<IndexInPlace> indexesInPlace) {

    final var missing = missingIndexes(needed, indexesInPlace);
    if (missing.isEmpty()) {
      return;
    }
    log
        .warn(
            """
                The MongoDB collection '{}' is missing {} of the indexes VanillaBP reads it by, \
                and '{}' is 'false', so nothing creates them. Run:
                  {}
                A question without its index reads the whole collection, and that costs more the \
                longer the application has been running.{}""",
            collection,
            missing.size(),
            PhaseTwoOutboxProperties.CREATE_SCHEMA_PROPERTY,
            missing
                .stream()
                .map(index -> index.createIndexOn(collection))
                .collect(Collectors.joining("\n  ")),
            whatAMissingUniqueIndexCostsOnTopOfThat(missing));

  }

  /**
   * The sentence about the unique index, appended where that one is among the missing. It
   * is the one index which is not about speed, so a message listing it next to the others
   * without a word would understate it.
   *
   * @param missing The indexes the collection is missing
   * @return The sentence, or an empty string
   */
  private static String whatAMissingUniqueIndexCostsOnTopOfThat(
      final List<MongoIndex> missing) {

    if (missing
        .stream()
        .noneMatch(MongoIndex::unique)) {
      return "";
    }
    return " The unique index is more than speed: without it two nodes writing the same "
        + "operation at the same moment both plan it, and the BPMS is called twice.";

  }

}
