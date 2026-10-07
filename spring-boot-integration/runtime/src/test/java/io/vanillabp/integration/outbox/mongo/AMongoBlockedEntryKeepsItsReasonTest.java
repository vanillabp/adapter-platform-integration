package io.vanillabp.integration.outbox.mongo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import org.bson.Document;
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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Filters;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoPermanentFailure;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * A blocked entry of the MongoDB store of Spring Boot names its reason in the document, in
 * the field <code>lastFailure</code>, with the same text the JDBC table keeps. Every write
 * which ends an attempt sets it: a failed attempt writes its reason, an attempt which goes
 * through removes it, and an entry opened again with the update of the wiki page keeps it
 * until its next attempt.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
@Testcontainers
public class AMongoBlockedEntryKeepsItsReasonTest {

  /**
   * The distance between attempts and the length of a claim. Short, so the attempts of a test
   * follow each other quickly.
   */
  private static final Duration SHORT = Duration.ofMillis(200);

  /**
   * How long a test waits for something it expects to happen. It is a guard against a machine
   * which leaves the JVM without a turn, not a measurement of speed.
   */
  private static final Duration UNTIL_IT_HAPPENED = Duration.ofSeconds(30);

  private static final String DATABASE = "blocked-entry-reason-test";

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "939";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB));

  @Mock
  private MigrationProcessService<Object> processService;

  private final AtomicInteger callsIntoTheAdapter = new AtomicInteger();

  private MongoTemplate mongoTemplate;

  private MongoClient mongoClient;

  @BeforeEach
  public void connect() {

    mongoClient = MongoClients.create(mongoDb.getConnectionString());
    mongoTemplate = new MongoTemplate(new SimpleMongoClientDatabaseFactory(mongoClient, DATABASE));

  }

  @AfterEach
  public void disconnect() {

    mongoClient.close();

  }

  private static PhaseTwoOutboxProperties allowingTwoAttempts() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setBlockAfterAttempts(2);
    properties.setAttemptFrequency(SHORT);
    properties.setPollInterval(SHORT);
    return properties;

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

  private MongoPhaseTwoOutboxDispatcher dispatcherOf(
      final PhaseTwoRouter router,
      final String collection) {

    return new MongoPhaseTwoOutboxDispatcher(
        mongoTemplate, theOnly(router), allowingTwoAttempts(), collection, theOnly(VanillaBpMetrics.NONE));

  }

  private static <T> ObjectProvider<T> theOnly(
      final T bean) {

    return new ObjectProvider<T>() {

      @Override
      public T getObject() {

        return bean;

      }

      @Override
      public Stream<T> stream() {

        return Stream.of(bean);

      }

    };

  }

  private String anEntryDueNow(
      final String collection) {

    final var id = UUID.randomUUID().toString();
    final var now = Date.from(Instant.now());
    mongoTemplate
        .getCollection(collection)
        .insertOne(new Document()
            .append("_id", id)
            .append("workflowModuleId", MODULE)
            .append("bpmnProcessId", PROCESS)
            .append("operation", PhaseOperation.CORRELATE_MESSAGE.name())
            .append("aggregateId", AGGREGATE)
            .append("adapterId", "dummy")
            .append("idempotencyKey", id)
            .append("dedupKey", id)
            .append("status", PhaseTwoOutboxEntry.STATUS_OPEN)
            .append("createdAt", now)
            .append("attempts", 0)
            .append("nextAttemptAt", now));
    return id;

  }

  private Document entryOf(
      final String collection,
      final String id) {

    final var document = mongoTemplate
        .getCollection(collection)
        .find(Filters.eq("_id", id))
        .first();
    assertNotNull(document, "the entry is gone");
    return document;

  }

  private static void waitUntil(
      final String whatDidNotHappen,
      final Callable<Boolean> itHappened) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED.toMillis();
    while (!itHappened.call()) {
      assertTrue(System.currentTimeMillis() < deadline, whatDidNotHappen);
      Thread.sleep(20);
    }

  }

  private Document dispatchedUntil(
      final String status,
      final MongoPhaseTwoOutboxDispatcher dispatcher,
      final String collection,
      final String id) throws Exception {

    try {
      dispatcher.startPolling();
      waitUntil(
          "the entry did not become "
              + status,
          () -> status.equals(entryOf(collection, id).getString("status")));
    } finally {
      dispatcher.stopPolling();
    }
    return entryOf(collection, id);

  }

  @Test
  @DisplayName("An entry blocked by a permanent failure names class and message of the failure")
  public void aPermanentFailureIsTheReason() throws Exception {

    final var collection = "outbox-reason-permanent";
    final var dispatcher = dispatcherOf(aRouterAnswering(call -> {
      throw new PhaseTwoPermanentFailure("the message 'Order paid' is not modelled", null);
    }), collection);
    final var id = anEntryDueNow(collection);

    final var entry = dispatchedUntil(PhaseTwoOutboxEntry.STATUS_BLOCKED, dispatcher, collection, id);

    assertEquals(
        "io.vanillabp.integration.spi.PhaseTwoPermanentFailure: the message 'Order paid' is not modelled",
        entry.getString("lastFailure"));

  }

  @Test
  @DisplayName("Every failed attempt writes its reason, and the last one is what the blocked entry keeps")
  public void theLastOfTheAttemptsIsTheReason() throws Exception {

    final var collection = "outbox-reason-attempts";
    final var id = anEntryDueNow(collection);
    final List<String> reasonBeforeTheSecondAttempt = new CopyOnWriteArrayList<>();
    final var dispatcher = dispatcherOf(aRouterAnswering(call -> {
      if (call == 2) {
        reasonBeforeTheSecondAttempt.add(entryOf(collection, id).getString("lastFailure"));
      }
      throw new IllegalStateException("the BPMS refused attempt "
          + call);
    }), collection);

    final var entry = dispatchedUntil(PhaseTwoOutboxEntry.STATUS_BLOCKED, dispatcher, collection, id);

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

    final var collection = "outbox-reason-opened-again";
    final var id = anEntryDueNow(collection);
    final var reason = dispatchedUntil(PhaseTwoOutboxEntry.STATUS_BLOCKED, dispatcherOf(aRouterAnswering(call -> {
      throw new PhaseTwoPermanentFailure("the message 'Order paid' is not modelled", null);
    }), collection), collection, id).getString("lastFailure");

    // the update the wiki page "Blocked outbox entries" gives an operator
    final var opened = mongoTemplate
        .getCollection(collection)
        .updateOne(
            new Document("_id", id).append("status", "BLOCKED"),
            new Document("$set", new Document("status", "OPEN").append("attempts", 0).append("nextAttemptAt",
                new Date()))
                .append("$unset", new Document("leasedBy", "").append("leasedUntil", "")));
    assertEquals(1, opened.getModifiedCount(), "the update of the wiki page did not open the entry");
    assertEquals(
        reason,
        entryOf(collection, id).getString("lastFailure"),
        "opening the entry again does not touch the reason - the next attempt does");

    Mockito.reset(processService);
    final var entry = dispatchedUntil(PhaseTwoOutboxEntry.STATUS_DONE, dispatcherOf(aRouterAnswering(call -> {
    }), collection), collection, id);

    assertFalse(entry.containsKey("lastFailure"), "the attempt which went through did not remove the reason");

  }

}
