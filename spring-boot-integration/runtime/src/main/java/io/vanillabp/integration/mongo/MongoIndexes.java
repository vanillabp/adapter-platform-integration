package io.vanillabp.integration.mongo;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.index.IndexField;

import io.vanillabp.integration.adapter.migration.mongo.MongoIndex;
import io.vanillabp.integration.adapter.migration.mongo.MongoSchema;
import lombok.extern.slf4j.Slf4j;

/**
 * What the MongoDB stores of this integration do with the index lists of
 * {@link MongoSchema}: create them, or report the ones an application managing its own
 * schema has not created. The Quarkus extension has a class of the same shape, and the
 * lists both of them walk are the same.
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
   * @param mongoTemplate The template creating them
   * @param collection The collection to create them on
   * @param needed What the store reads by
   */
  public static void createOn(
      final MongoTemplate mongoTemplate,
      final String collection,
      final List<MongoIndex> needed) {

    needed
        .forEach(index -> mongoTemplate
            .indexOps(collection)
            .createIndex(asSpringIndex(index)));

  }

  /**
   * Warns about every index the collection is missing, with the statement which creates
   * it.
   *
   * @param mongoTemplate The template reading what is there
   * @param collection The collection to look at
   * @param needed What the store reads by
   */
  public static void reportMissingOn(
      final MongoTemplate mongoTemplate,
      final String collection,
      final List<MongoIndex> needed) {

    MongoSchema.reportMissingIndexes(collection, needed, indexesInPlace(mongoTemplate, collection));

  }

  /**
   * @param index The index to create
   * @return The same index in the words of Spring Data
   */
  private static Index asSpringIndex(
      final MongoIndex index) {

    final var springIndex = new Index();
    index
        .fields()
        .forEach(field -> springIndex.on(field, Sort.Direction.ASC));
    if (index.unique()) {
      springIndex.unique();
    }
    if (index.sparse()) {
      springIndex.sparse();
    }
    return springIndex;

  }

  /**
   * What the collection carries today. A collection which does not exist yet carries
   * nothing, and on MongoDB that is the normal state of an application which has not
   * written its first document - so the database saying so is not an error here.
   *
   * @param mongoTemplate The template reading what is there
   * @param collection The collection to ask
   * @return The indexes it carries, without MongoDB's own one over the document id
   */
  private static List<MongoSchema.IndexInPlace> indexesInPlace(
      final MongoTemplate mongoTemplate,
      final String collection) {

    try {
      return mongoTemplate
          .indexOps(collection)
          .getIndexInfo()
          .stream()
          .map(info -> new MongoSchema.IndexInPlace(
              info
                  .getIndexFields()
                  .stream()
                  .map(IndexField::getKey)
                  .toList(), info.isUnique()))
          .toList();
    } catch (final RuntimeException e) {
      log.debug("The indexes of collection '{}' could not be read", collection, e);
      return List.of();
    }

  }

}
