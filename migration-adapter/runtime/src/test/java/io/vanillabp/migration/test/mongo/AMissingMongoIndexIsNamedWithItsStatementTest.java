package io.vanillabp.migration.test.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.mongo.MongoIndex;
import io.vanillabp.integration.adapter.migration.mongo.MongoSchema;
import io.vanillabp.integration.adapter.migration.mongo.MongoSchema.IndexInPlace;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What an application on MongoDB is told when it manages its schema itself.
 * <p>
 * A collection is created by the first document, so there is nothing to verify there. The
 * indexes are the whole of it: nothing creates them where
 * <code>vanillabp.outbox.create-schema</code> is <code>false</code>, and without a word at
 * startup the application meets them as a query which got slow, months later. So the
 * startup names each missing one together with the statement which creates it, the way the
 * relational stores name theirs.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AMissingMongoIndexIsNamedWithItsStatementTest {

  private static final String COLLECTION = "vanillabp-phase-two-outbox";

  @Test
  @DisplayName("A collection which does not exist yet is missing every index, and each one is named")
  public void everyIndexOfAFreshCollectionIsNamed() {

    final var reported = whileReporting(
        () -> MongoSchema.reportMissingIndexes(COLLECTION, MongoSchema.OUTBOX_INDEXES, List.of()));

    assertEquals(1, reported.size(), "one message and not one per index: "
        + reported);
    final var message = reported.get(0);
    assertEquals(Level.WARN, message.getLevel(), "the application boots, it is only slower");
    final var text = message.getFormattedMessage();
    assertTrue(text.contains(COLLECTION), text);
    assertTrue(
        text.contains("'%s'".formatted(PhaseTwoOutboxProperties.CREATE_SCHEMA_PROPERTY)),
        "the message names the property which would have created them: "
            + text);
    MongoSchema.OUTBOX_INDEXES
        .forEach(index -> assertTrue(
            text.contains(index.createIndexOn(COLLECTION)),
            () -> "the statement creating %s is missing from: %s".formatted(index.fields(), text)));
    assertTrue(
        text.contains(
            "db.getCollection(\"vanillabp-phase-two-outbox\").createIndex({ \"status\": 1, \"createdAt\": 1 });"),
        () -> "the statement has to be one a developer pastes into mongosh: "
            + text);
    assertTrue(
        text.contains("{ \"unique\": true }"),
        () -> "and the unique one has to stay unique: "
            + text);
    assertTrue(
        text.contains("two nodes"),
        () -> "the unique index is not about speed, and the message says so: "
            + text);

  }

  @Test
  @DisplayName("A collection which carries everything is not reported")
  public void aCompleteCollectionIsNotReported() {

    final var reported = whileReporting(
        () -> MongoSchema
            .reportMissingIndexes(COLLECTION, MongoSchema.OUTBOX_INDEXES, everythingOf(MongoSchema.OUTBOX_INDEXES)));

    assertTrue(reported.isEmpty(), "nothing is missing, so nothing is said: "
        + reported);

  }

  @Test
  @DisplayName("An index over the same fields in another order does not answer the question")
  public void theOrderOfTheFieldsDecides() {

    final var theOtherWayRound = new IndexInPlace(List.of("nextAttemptAt", "status"), false);
    final var dueEntries = MongoSchema.OUTBOX_INDEXES
        .stream()
        .filter(index -> index.fields().equals(List.of("status", "nextAttemptAt")))
        .findFirst()
        .orElseThrow();

    assertFalse(
        dueEntries.isServedBy(theOtherWayRound),
        "MongoDB reads an index from the left, so the order is part of the index");

  }

  @Test
  @DisplayName("An index over the deduplication key which lets a duplicate through is still missing")
  public void anIndexWhichIsNotUniqueDoesNotServeTheUniqueOne() {

    final var withoutItsUniqueness = List.of(new IndexInPlace(List.of("dedupKey"), false));

    final var missing = MongoSchema.missingIndexes(MongoSchema.OUTBOX_INDEXES, withoutItsUniqueness);

    assertTrue(
        missing
            .stream()
            .anyMatch(index -> index.fields().equals(List.of("dedupKey"))),
        "an index which does not refuse the second document deduplicates nothing: "
            + missing);

  }

  @Test
  @DisplayName("An index spanning every document serves a sparse one")
  public void anIndexOverEveryDocumentServesASparseOne() {

    final var overEveryDocument = List
        .of(new IndexInPlace(List.of("args.payloadReference"), false));
    final var payloadReferences = MongoSchema.OUTBOX_INDEXES
        .stream()
        .filter(MongoIndex::sparse)
        .findFirst()
        .orElseThrow();

    assertTrue(
        payloadReferences.isServedBy(overEveryDocument.get(0)),
        "sparse keeps an index small and changes no answer");

  }

  /**
   * @param needed The indexes to pretend the collection carries
   * @return Those indexes as the database would report them
   */
  private static List<IndexInPlace> everythingOf(
      final List<MongoIndex> needed) {

    return needed
        .stream()
        .map(index -> new IndexInPlace(index.fields(), index.unique()))
        .toList();

  }

  /**
   * @param reporting What is run while the log is watched
   * @return What it logged
   */
  private static List<ILoggingEvent> whileReporting(
      final Runnable reporting) {

    final var root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    final var recorded = new ListAppender<ILoggingEvent>();
    recorded.start();
    root.addAppender(recorded);
    try {
      reporting.run();
    } finally {
      root.detachAppender(recorded);
      recorded.stop();
    }
    return List.copyOf(recorded.list);

  }

}
