package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.mongo.MongoSchema;
import io.vanillabp.integration.runtime.mongo.MongoIndexes;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * What <code>vanillabp.outbox.create-schema: false</code> means on MongoDB: VanillaBP
 * creates no index, and a collection appears with the first document rather than at
 * startup. So the only thing such an application can be told is which indexes it owes, and
 * the startup names them with the statement which creates each one.
 * <p>
 * The message itself is held by the test of the core which writes it. What is tested here
 * is the half only a database answers: an index VanillaBP created has to be an index
 * VanillaBP recognizes again, or every startup would name indexes which are there.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoIndexesOfAnApplicationManagingItsOwnSchemaTest {

  private static final String DATABASE = "missing-indexes-it";

  /**
   * A database of its own for the round trip below, so the assertion that the startup
   * created nothing does not meet the indexes this test creates itself.
   */
  private static final String DATABASE_OF_THE_ROUND_TRIP = "indexes-read-back-it";

  private static final String OUTBOX_COLLECTION = PhaseTwoOutboxProperties.MongoOutboxProperties.DEFAULT_COLLECTION;

  /**
   * The hour before the last one, whenever this test runs. A window from it to the hour
   * after it never contains the present moment, whatever time of day that is - a window
   * whose end lies before its start crosses midnight, and this one does that exactly when
   * the two hours lie on either side of it.
   */
  private static final java.time.LocalTime AN_HOUR_WHICH_IS_OVER = java.time.LocalTime
      .now()
      .minusHours(2)
      .withNano(0);

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", DATABASE)
      .overrideConfigKey("vanillabp.outbox.create-schema", "false")
      // the application.yaml of this module leaves the housekeeping window open all day,
      // which every other test here needs. This one asks what the STARTUP created, and a
      // housekeeping which runs writes its claim - a document, and with it a collection.
      // So the window is shut for this test: an hour which is over whenever it runs
      .overrideConfigKey("vanillabp.outbox.housekeeping.start", AN_HOUR_WHICH_IS_OVER.toString())
      .overrideConfigKey("vanillabp.outbox.housekeeping.end", AN_HOUR_WHICH_IS_OVER.plusHours(1).toString());

  @Inject
  MongoClient mongoClient;

  @Test
  @DisplayName("Nothing is created where the application said that it looks after its schema")
  public void theStartupCreatesNothing() {

    final var collections = new ArrayList<String>();
    mongoClient
        .getDatabase(DATABASE)
        .listCollectionNames()
        .forEach(collections::add);

    assertEquals(List.of(), collections, "a collection here would carry an index VanillaBP created");

  }

  @Test
  @DisplayName("An index VanillaBP created is one it recognizes again")
  public void whatWasCreatedIsRecognizedAgain() {

    final var collection = mongoClient
        .getDatabase(DATABASE_OF_THE_ROUND_TRIP)
        .getCollection(OUTBOX_COLLECTION, Document.class);
    collection.drop();

    assertEquals(
        MongoSchema.OUTBOX_INDEXES,
        MongoSchema.missingIndexes(MongoSchema.OUTBOX_INDEXES, indexesOf(collection)),
        "a collection which does not exist yet owes every one of them");

    MongoIndexes.createOn(collection, MongoSchema.OUTBOX_INDEXES);

    assertEquals(
        List.of(),
        MongoSchema.missingIndexes(MongoSchema.OUTBOX_INDEXES, indexesOf(collection)),
        "and what was created has to answer for them, unique and field order included");

  }

  /**
   * What the collection carries, read the way the startup reads it.
   *
   * @param collection The collection to ask
   * @return Its indexes
   */
  private static List<MongoSchema.IndexInPlace> indexesOf(
      final MongoCollection<Document> collection) {

    final var found = new ArrayList<MongoSchema.IndexInPlace>();
    collection
        .listIndexes()
        .forEach(index -> found.add(
            new MongoSchema.IndexInPlace(
                List.copyOf(index.get("key", Document.class).keySet()), Boolean.TRUE
                    .equals(index.getBoolean("unique")))));
    return found;

  }

}
