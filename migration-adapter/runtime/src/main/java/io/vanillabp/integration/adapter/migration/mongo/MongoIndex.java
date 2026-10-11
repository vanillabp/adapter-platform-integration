package io.vanillabp.integration.adapter.migration.mongo;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One index which a MongoDB collection of VanillaBP needs. It is described once for both
 * platform integrations. It names the fields the index spans, whether it is unique, whether
 * it is sparse, and the question it answers.
 * <p>
 * Both platforms create their indexes from the lists in {@link MongoSchema}. Both also ask
 * these lists what an existing collection is missing. So an index cannot be created on one
 * platform and forgotten on the other.
 *
 * @param fields The fields it spans, in the order the questions read them
 * @param unique Whether the database refuses a second document with those values
 * @param sparse Whether a document which does not carry the field stays out of it
 * @param whatItIsReadBy The question it answers, written for the developer who has to
 *        create it themselves
 */
public record MongoIndex(
                         List<String> fields,
                         boolean unique,
                         boolean sparse,
                         String whatItIsReadBy) {

  /**
   * An ascending index over one or more fields. Every question of VanillaBP reads the
   * fields in ascending order.
   *
   * @param whatItIsReadBy The question it answers, written for the developer who has to
   *          create it themselves
   * @param fields The fields it spans, in the order the questions read them
   * @return The index
   */
  public static MongoIndex readBy(
      final String whatItIsReadBy,
      final String... fields) {

    return new MongoIndex(List.of(fields), false, false, whatItIsReadBy);

  }

  /**
   * An index which refuses a second document with the same values, over one or more
   * fields.
   *
   * @param whatItIsReadBy The question it answers, written for the developer who has to
   *          create it themselves
   * @param fields The fields it spans, in the order the questions read them
   * @return The index
   */
  public static MongoIndex uniqueIndexReadBy(
      final String whatItIsReadBy,
      final String... fields) {

    return new MongoIndex(List.of(fields), true, false, whatItIsReadBy);

  }

  /**
   * A sparse index over one or more fields. A document which does not carry the field
   * stays out of it.
   *
   * @param whatItIsReadBy The question it answers, written for the developer who has to
   *          create it themselves
   * @param fields The fields it spans, in the order the questions read them
   * @return The index
   */
  public static MongoIndex sparseIndexReadBy(
      final String whatItIsReadBy,
      final String... fields) {

    return new MongoIndex(List.of(fields), false, true, whatItIsReadBy);

  }

  /**
   * Whether an index which the collection already has does the job of this one. The
   * fields decide, in their order, because MongoDB answers a question from them. Only a
   * unique index can do the job of a unique one. An index which lets a second document in
   * would answer the question, but it would no longer refuse the duplicate.
   * <p>
   * Sparse is not compared. It keeps an index small and changes no answer. So an index over
   * every document does the job of a sparse one.
   *
   * @param indexInPlace An index the collection carries
   * @return Whether that index does what this one is there for
   */
  public boolean isServedBy(
      final MongoSchema.IndexInPlace indexInPlace) {

    return fields.equals(indexInPlace.fields()) && (!unique || indexInPlace.unique());

  }

  /**
   * The statement which creates this index, written so that a developer can paste it into
   * <code>mongosh</code>. It names the collection with <code>getCollection</code>. The
   * default names contain hyphens, and the shorter <code>db.name</code> form would read a
   * hyphen as a minus sign.
   *
   * @param collection The collection to create it on
   * @return The statement, ending with a semicolon and the question it answers
   */
  public String createIndexOn(
      final String collection) {

    final var keys = fields
        .stream()
        .map("\"%s\": 1"::formatted)
        .collect(Collectors.joining(", "));
    final var options = options();
    return "db.getCollection(\"%s\").createIndex({ %s }%s);   // %s"
        .formatted(collection, keys, options, whatItIsReadBy);

  }

  /**
   * @return What follows the keys in the statement, and an empty string for an index which
   *         needs neither
   */
  private String options() {

    if (unique && sparse) {
      return ", { \"unique\": true, \"sparse\": true }";
    }
    if (unique) {
      return ", { \"unique\": true }";
    }
    if (sparse) {
      return ", { \"sparse\": true }";
    }
    return "";

  }

}
