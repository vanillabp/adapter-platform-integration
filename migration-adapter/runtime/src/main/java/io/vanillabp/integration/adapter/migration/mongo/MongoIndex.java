package io.vanillabp.integration.adapter.migration.mongo;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One index a MongoDB collection of VanillaBP needs, described once for both platform
 * integrations: the fields it spans, whether it is unique, whether it is sparse, and the
 * question it answers.
 * <p>
 * Both platforms create their indexes from the lists in {@link MongoSchema} and both ask
 * the same lists what an existing collection is missing, so an index cannot be created on
 * one platform and forgotten on the other.
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
   * An index over one or more fields, ascending, which is the order every question of
   * VanillaBP reads them in.
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
   * An index a document which does not carry the field stays out of, over one or more
   * fields.
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
   * Whether an index a collection carries today does what this one is there for. The
   * fields decide, in their order, because that is what MongoDB answers a question from. A
   * unique index is only served by a unique one: an index which lets a second document
   * through would answer the question and stop refusing the duplicate.
   * <p>
   * Sparse is not compared. It keeps an index small and changes no answer, so an index
   * spanning every document serves a sparse one.
   *
   * @param indexInPlace An index the collection carries
   * @return Whether that index does what this one is there for
   */
  public boolean isServedBy(
      final MongoSchema.IndexInPlace indexInPlace) {

    return fields.equals(indexInPlace.fields()) && (!unique || indexInPlace.unique());

  }

  /**
   * The statement which creates this index, written the way a developer pastes it into
   * <code>mongosh</code>. The collection is named through <code>getCollection</code>
   * because the default names carry hyphens, which the shorter <code>db.name</code> form
   * reads as a subtraction.
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
