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
 * is the half only a database answers: no index was created, and an index VanillaBP
 * created has to be an index VanillaBP recognizes again, or every startup would name
 * indexes which are there.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoIndexesOfAnApplicationManagingItsOwnSchemaTest {

  private static final String DATABASE = "missing-indexes-it";

  /**
   * A database of its own for the round trip below, so the assertion about what the
   * startup created does not meet the indexes this test creates itself.
   */
  private static final String DATABASE_OF_THE_ROUND_TRIP = "indexes-read-back-it";

  private static final String OUTBOX_COLLECTION = PhaseTwoOutboxProperties.MongoOutboxProperties.DEFAULT_COLLECTION;

  /**
   * The index MongoDB creates itself, over the id of a document. Every collection carries
   * it and nobody asked VanillaBP for it.
   */
  private static final String THE_INDEX_MONGODB_CREATES_ITSELF = "_id_";

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
      // the housekeeping window is held open on purpose, the way the two tests which
      // watch the housekeeping do it. A window which ends before it starts crosses
      // midnight, so this one is open all day but for the first minute of it.
      //
      // The housekeeping claims its store by a write, and on MongoDB a write makes the
      // collection it goes to. So the lease collection appears here although nobody
      // created a schema. An assertion over the collection names was green all day and
      // red between 04:00 and 05:00, which is the default window read in the zone the
      // test JVMs of this build stand in. Holding the window open makes that write
      // happen in every run, and what is asserted below is the promise itself: no index
      .overrideConfigKey("vanillabp.outbox.housekeeping.start", "00:01")
      .overrideConfigKey("vanillabp.outbox.housekeeping.end", "00:00")
      .overrideConfigKey("vanillabp.outbox.housekeeping.zone", "Europe/Vienna");

  @Inject
  MongoClient mongoClient;

  @Test
  @DisplayName("No index is created where the application said that it looks after its schema")
  public void theStartupCreatesNoIndex() {

    final var database = mongoClient.getDatabase(DATABASE);

    final var created = new ArrayList<String>();
    database
        .listCollectionNames()
        .forEach(collection -> database
            .getCollection(collection, Document.class)
            .listIndexes()
            .forEach(index -> {
              final var name = index.getString("name");
              if (!THE_INDEX_MONGODB_CREATES_ITSELF.equals(name)) {
                created.add("%s.%s".formatted(collection, name));
              }
            }));

    assertEquals(
        List.of(), created, "VanillaBP created these although the application looks after its schema");

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
