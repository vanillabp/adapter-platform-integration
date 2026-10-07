package io.vanillabp.integration.runtime.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.eclipse.microprofile.config.spi.ConfigProviderResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.test.InstanceDouble;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoPermanentFailure;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * A blocked entry of the MongoDB store of Quarkus names its reason in the document, in the
 * field <code>lastFailure</code>, with the same text the JDBC table keeps. Every write which
 * ends an attempt sets it: a failed attempt writes its reason, an attempt which goes through
 * removes it, and an entry opened again with the update of the wiki page keeps it until its
 * next attempt.
 * <p>
 * The Spring Boot store has the same test. The two dispatchers are two classes, so what is
 * asked here is whether THIS one writes the field. Like the other tests of this dispatcher it
 * sits in the package of the class, because the test fills the injection points the way the
 * container does.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
@Testcontainers
public class MongoBlockedEntryKeepsItsReasonTest {

  /**
   * The distance between attempts and the length of a claim. Short, so the attempts of a test
   * follow each other quickly.
   */
  private static final String SHORT = "PT0.2S";

  private static final long UNTIL_IT_HAPPENED = 30_000;

  private static final String DATABASE = "blocked-entry-reason-test";

  private static final String COLLECTION = "outbox-blocked-entry-reason";

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "939";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB));

  @Mock
  private MigrationProcessService<Object> processService;

  private final AtomicInteger callsIntoTheAdapter = new AtomicInteger();

  private MongoClient mongoClient;

  private MongoCollection<Document> outbox;

  private SmallRyeConfig config;

  @BeforeEach
  public void connectAndConfigure() {

    mongoClient = MongoClients.create(mongoDb.getConnectionString());
    outbox = mongoClient
        .getDatabase(DATABASE)
        .getCollection(COLLECTION);
    outbox.deleteMany(new Document());

    config = new SmallRyeConfigBuilder()
        .withMapping(QuarkusMigrationAdapterProperties.class)
        .withSources(
            new PropertiesConfigSource(
                Map
                    .of(
                        "quarkus.mongodb.database", DATABASE,
                        "vanillabp.outbox.mongo.collection", COLLECTION,
                        "vanillabp.outbox.block-after-attempts", "2",
                        "vanillabp.outbox.attempt-frequency", SHORT,
                        "vanillabp.outbox.poll-interval", SHORT), "the test", 500))
        .build();
    ConfigProviderResolver
        .instance()
        .registerConfig(config, Thread.currentThread().getContextClassLoader());

  }

  @AfterEach
  public void disconnect() {

    ConfigProviderResolver
        .instance()
        .releaseConfig(config);
    mongoClient.close();

  }

  @FunctionalInterface
  private interface Answer {

    void dispatch(
        int call) throws Exception;

  }

  private PhaseTwoRouter aRouterAnswering(
      final Answer answer) {

    when(processService.getWorkflowModuleId()).thenReturn(MODULE);
    when(processService.getBpmnProcessId()).thenReturn(PROCESS);
    when(processService.convertAggregateId(AGGREGATE)).thenReturn(AGGREGATE);
    Mockito
        .doAnswer(invocation -> {
          answer.dispatch(callsIntoTheAdapter.incrementAndGet());
          return null;
        })
        .when(processService)
        .executePhaseTwo(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
    final var router = new PhaseTwoRouter();
    router.register(processService);
    return router;

  }

  /**
   * The real dispatcher on the real collection, with its injection points filled the way the
   * container fills them.
   */
  private MongoPhaseTwoOutboxDispatcher dispatcherOf(
      final PhaseTwoRouter router) {

    final var dispatcher = new MongoPhaseTwoOutboxDispatcher();
    dispatcher.mongoClient = InstanceDouble.of(List.of(mongoClient));
    dispatcher.phaseTwoRouter = InstanceDouble.of(List.of(router));
    dispatcher.vanillaBpMetrics = InstanceDouble.of(List.of(VanillaBpMetrics.NONE));
    return dispatcher;

  }

  private String anEntryDueNow() {

    final var id = UUID.randomUUID().toString();
    final var now = Date.from(Instant.now());
    outbox
        .insertOne(new Document()
            .append("_id", id)
            .append("workflowModuleId", MODULE)
            .append("bpmnProcessId", PROCESS)
            .append("operation", PhaseOperation.CORRELATE_MESSAGE.name())
            .append("aggregateId", AGGREGATE)
            .append("adapterId", "dummy")
            .append("idempotencyKey", id)
            .append("dedupKey", id)
            .append("status", MongoPhaseTwoOutbox.STATUS_OPEN)
            .append("createdAt", now)
            .append("attempts", 0)
            .append("nextAttemptAt", now));
    return id;

  }

  private Document entryOf(
      final String id) {

    final var document = outbox
        .find(Filters.eq("_id", id))
        .first();
    assertNotNull(document, "the entry is gone");
    return document;

  }

  private static void waitUntil(
      final String whatDidNotHappen,
      final Callable<Boolean> itHappened) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED;
    while (!itHappened.call()) {
      assertTrue(System.currentTimeMillis() < deadline, whatDidNotHappen);
      Thread.sleep(20);
    }

  }

  private Document dispatchedUntil(
      final String status,
      final MongoPhaseTwoOutboxDispatcher dispatcher,
      final String id) throws Exception {

    try {
      dispatcher.onStart(new StartupEvent());
      waitUntil("the entry did not become "
          + status, () -> status.equals(entryOf(id).getString("status")));
    } finally {
      dispatcher.shutdown();
    }
    return entryOf(id);

  }

  @Test
  @DisplayName("An entry blocked by a permanent failure names class and message of the failure")
  public void aPermanentFailureIsTheReason() throws Exception {

    final var dispatcher = dispatcherOf(aRouterAnswering(call -> {
      throw new PhaseTwoPermanentFailure("the message 'Order paid' is not modelled", null);
    }));
    final var id = anEntryDueNow();

    final var entry = dispatchedUntil(MongoPhaseTwoOutbox.STATUS_BLOCKED, dispatcher, id);

    assertEquals(
        "io.vanillabp.integration.spi.PhaseTwoPermanentFailure: the message 'Order paid' is not modelled",
        entry.getString("lastFailure"));

  }

  @Test
  @DisplayName("Every failed attempt writes its reason, and the last one is what the blocked entry keeps")
  public void theLastOfTheAttemptsIsTheReason() throws Exception {

    final var id = anEntryDueNow();
    final List<String> reasonBeforeTheSecondAttempt = new CopyOnWriteArrayList<>();
    final var dispatcher = dispatcherOf(aRouterAnswering(call -> {
      if (call == 2) {
        reasonBeforeTheSecondAttempt.add(entryOf(id).getString("lastFailure"));
      }
      throw new IllegalStateException("the BPMS refused attempt "
          + call);
    }));

    final var entry = dispatchedUntil(MongoPhaseTwoOutbox.STATUS_BLOCKED, dispatcher, id);

    assertEquals(
        List.of("java.lang.IllegalStateException: the BPMS refused attempt 1"),
        reasonBeforeTheSecondAttempt,
        "an entry which is still being repeated says why its first attempt failed");
    assertEquals(2, entry.getInteger("attempts"));
    assertEquals("java.lang.IllegalStateException: the BPMS refused attempt 2", entry.getString("lastFailure"));

  }

  @Test
  @DisplayName("An entry opened again keeps its reason until an attempt goes through and removes it")
  public void openingAgainKeepsTheReasonUntilTheNextAttempt() throws Exception {

    final var id = anEntryDueNow();
    final var reason = dispatchedUntil(MongoPhaseTwoOutbox.STATUS_BLOCKED, dispatcherOf(aRouterAnswering(call -> {
      throw new PhaseTwoPermanentFailure("the message 'Order paid' is not modelled", null);
    })), id).getString("lastFailure");

    // the update the wiki page "Blocked outbox entries" gives an operator
    final var opened = outbox
        .updateOne(
            new Document("_id", id).append("status", "BLOCKED"),
            new Document("$set", new Document("status", "OPEN").append("attempts", 0).append("nextAttemptAt",
                new Date()))
                .append("$unset", new Document("leasedBy", "").append("leasedUntil", "")));
    assertEquals(1, opened.getModifiedCount(), "the update of the wiki page did not open the entry");
    assertEquals(
        reason,
        entryOf(id).getString("lastFailure"),
        "opening the entry again does not touch the reason - the next attempt does");

    Mockito.reset(processService);
    final var entry = dispatchedUntil(MongoPhaseTwoOutbox.STATUS_DONE, dispatcherOf(aRouterAnswering(call -> {
    })), id);

    assertFalse(entry.containsKey("lastFailure"), "the attempt which went through did not remove the reason");

  }

}
