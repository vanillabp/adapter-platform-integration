package io.vanillabp.integration.runtime.mongo;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.bson.conversions.Bson;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;

import io.vanillabp.integration.adapter.migration.mongo.MongoIndex;
import io.vanillabp.integration.adapter.migration.mongo.MongoSchema;
import lombok.extern.slf4j.Slf4j;

/**
 * What the MongoDB stores of this extension do with the index lists of
 * {@link MongoSchema}: create them, or report the ones an application managing its own
 * schema has not created. The Spring Boot integration has a class of the same shape, and
 * the lists both of them walk are the same.
 */
@Slf4j
public final class MongoIndexes {

  private MongoIndexes() {
  }

  /**
   * Creates the indexes which are not there yet. MongoDB answers a
   * <code>createIndex</code> of an index which is already there with its name, so two
   * instances starting at the same moment do not collide over it.
   *
   * @param collection The collection to create them on
   * @param needed What the store reads by
   */
  public static void createOn(
      final MongoCollection<Document> collection,
      final List<MongoIndex> needed) {

    needed.forEach(index -> collection.createIndex(keysOf(index), optionsOf(index)));

  }

  /**
   * Warns about every index the collection is missing, with the statement which creates
   * it.
   *
   * @param collection The collection to look at, which the message names as the
   *          application spelled it
   * @param needed What the store reads by
   */
  public static void reportMissingOn(
      final MongoCollection<Document> collection,
      final List<MongoIndex> needed) {

    MongoSchema
        .reportMissingIndexes(
            collection
                .getNamespace()
                .getCollectionName(),
            needed,
            indexesInPlace(collection));

  }

  /**
   * @param index The index to create
   * @return The fields of the index, ascending
   */
  private static Bson keysOf(
      final MongoIndex index) {

    return Indexes.ascending(index.fields());

  }

  /**
   * @param index The index to create
   * @return What the database is told about it besides its fields
   */
  private static IndexOptions optionsOf(
      final MongoIndex index) {

    return new IndexOptions()
        .unique(index.unique())
        .sparse(index.sparse());

  }

  /**
   * What the collection carries today. A collection which does not exist yet carries
   * nothing, and on MongoDB that is the normal state of an application which has not
   * written its first document - so the database saying so is not an error here.
   *
   * @param collection The collection to ask
   * @return The indexes it carries, without MongoDB's own one over the document id
   */
  private static List<MongoSchema.IndexInPlace> indexesInPlace(
      final MongoCollection<Document> collection) {

    final var found = new ArrayList<MongoSchema.IndexInPlace>();
    try {
      collection
          .listIndexes()
          .forEach(index -> found.add(
              new MongoSchema.IndexInPlace(
                  List.copyOf(index.get("key", Document.class).keySet()), Boolean.TRUE
                      .equals(index.getBoolean("unique")))));
    } catch (final RuntimeException e) {
      log.debug("The indexes of collection '{}' could not be read", collection.getNamespace(), e);
    }
    return found;

  }

}
