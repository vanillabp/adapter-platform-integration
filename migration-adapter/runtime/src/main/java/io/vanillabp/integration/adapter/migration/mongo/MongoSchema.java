package io.vanillabp.integration.adapter.migration.mongo;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.spi.PhaseTwoCall;
import lombok.extern.slf4j.Slf4j;

/**
 * The indexes which the MongoDB stores of VanillaBP use, and what the startup says about
 * missing ones. Both platform integrations create the indexes from the lists below. Both
 * also compare an existing collection with the same lists.
 * {@link io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema} does the same for the
 * relational stores.
 * <p>
 * On MongoDB nobody has to create a collection. It appears with the first document. So with
 * <code>vanillabp.outbox.create-schema: false</code> only the indexes are left to check.
 * That is why a missing index is reported but does not stop the boot. Every question is
 * still answered, it just reads the whole collection instead of an index. The relational
 * stores report missing indexes the same way. They stop the boot only where a table or a
 * column is missing, and MongoDB has nothing like that.
 */
@Slf4j
public final class MongoSchema {

  private MongoSchema() {
  }

  /**
   * The indexes of the outbox collection
   * (<code>vanillabp.outbox.mongo.collection</code>).
   * <p>
   * Three of them filter by the status and then read a different point in time each. That
   * needs three indexes. One index over all three points in time would serve none of the
   * three questions.
   */
  public static final List<MongoIndex> OUTBOX_INDEXES = List
      .of(
          // 'dedupKey' and not 'idempotencyKey'. The key removes duplicates among the
          // operations which still wait for their dispatch. Once an entry is dispatched,
          // the field holds the entry's own id. Every entry has the field, so the index
          // needs neither sparse nor a partial filter
          MongoIndex.uniqueIndexReadBy("what keeps one operation from being planned twice", "dedupKey"),
          MongoIndex.readBy("which entry is due next, and the claim which picks it up", "status", "nextAttemptAt"),
          MongoIndex.readBy("what the retention may delete, and the delete itself", "status", "doneAt"),
          MongoIndex.readBy("how long the oldest waiting entry has waited", "status", "createdAt"),
          // only an entry with a payload has this field, and few entries have one
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
   * (<code>vanillabp.outbox.mongo.delivery-collection</code>). MongoDB has no limit on the
   * length of a key. So here the aggregate id itself has an index. In the relational table
   * the column is too wide for one.
   */
  public static final List<MongoIndex> DELIVERY_INDEXES = List
      .of(
          MongoIndex.readBy("which records the retention deletes by age", "lastSeenAt"),
          MongoIndex.readBy("the record of the task an operation names", "taskId"),
          MongoIndex.readBy("the open tasks of one workflow aggregate", "aggregateId"),
          MongoIndex.readBy("the open tasks of one workflow of the BPMS", "workflowId"));

  /**
   * One index which a collection has today. The platform integration reads it back from the
   * database.
   *
   * @param fields The fields it spans, in the order the database holds them
   * @param unique Whether it refuses a second document with those values
   */
  public record IndexInPlace(
                             List<String> fields,
                             boolean unique) {
  }

  /**
   * The needed indexes whose job no existing index does.
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
   * Warns about every index which a collection is missing, and names the statement which
   * creates it. It is called where the application manages its schema itself
   * (<code>vanillabp.outbox.create-schema: false</code>). Nothing else tells such an
   * application what MongoDB needs.
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
   * The sentence about the unique index, added where that index is missing. It is the only
   * index which is not about speed. A message which listed it next to the others without a
   * word would make it look harmless.
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
