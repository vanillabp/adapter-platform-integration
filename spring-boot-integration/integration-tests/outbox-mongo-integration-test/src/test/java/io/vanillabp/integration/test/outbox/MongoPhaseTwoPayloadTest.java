package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.ConnectionString;

import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * A payload on the MongoDB store: the document with the bytes is written in the very
 * transaction which writes the outbox entry, the dispatch reads it back, and it is gone
 * once the entry was marked DONE.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = {
    TestApplication.class, MongoPhaseTwoPayloadTest.MongoPayloadTestConfiguration.class
})
@Testcontainers
@DirtiesContext
public class MongoPhaseTwoPayloadTest {

  private static final String PAYLOAD_COLLECTION = "vanillabp-phase-two-payloads";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse("mongo:5.0"))
      // MongoDB transactions require a replica set
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  @TestConfiguration
  static class MongoPayloadTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {

      return builder -> builder.applyConnectionString(new ConnectionString(mongoDb.getReplicaSetUrl()));

    }

    @Bean
    PayloadExtension payloadExtension(
        final io.vanillabp.integration.spi.PhaseOperationRegistry registry) {

      return new PayloadExtension(registry);

    }

  }

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private PhaseTwoOutbox outbox;

  @Autowired
  private PayloadExtension extension;

  @org.junit.jupiter.api.BeforeEach
  public void resetExtension() {

    extension.reset();

  }

  private long countPayloads() {

    return mongoTemplate.getCollection(PAYLOAD_COLLECTION).countDocuments();

  }

  private long countPayloadsOf(
      final String reference) {

    return mongoTemplate
        .getCollection(PAYLOAD_COLLECTION)
        .countDocuments(new org.bson.Document("_id", reference));

  }

  @Test
  @DisplayName("A payload travels by reference and is gone once the entry was dispatched")
  public void aPayloadTravelsByReference() throws Exception {

    final var state = "{\"amount\":42}".getBytes(StandardCharsets.UTF_8);

    final var reference = new java.util.concurrent.atomic.AtomicReference<String>();
    final var aggregate = transactionTemplate.execute(status -> {
      final var newAggregate = new Aggregate();
      newAggregate.setContent("payload");
      final var attached = processService.startWorkflow(newAggregate);
      final var call = PayloadExtension.call(attached.getId(), "created", state);
      reference.set(call.payloadReference());
      outbox.schedule(call);
      return attached;
    });
    assertNotNull(aggregate);
    // the bytes rode the very transaction the aggregate rode
    assertEquals(1L, countPayloadsOf(reference.get()));

    final var dispatched = extension.awaitDispatched(1, 20000);
    final var call = dispatched.getFirst();
    assertEquals(reference.get(), call.payloadReference());
    assertArrayEquals(state, call.payload());

    final var deadline = System.currentTimeMillis() + 20000;
    while (countPayloadsOf(reference.get()) > 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the payload '%s' was never removed".formatted(reference.get()));
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("A call without a payload writes no document into the payload collection")
  public void aCallWithoutAPayloadStoresNothing() throws Exception {

    final var before = countPayloads();

    final var aggregate = transactionTemplate.execute(status -> {
      final var newAggregate = new Aggregate();
      newAggregate.setContent("no-payload");
      final var attached = processService.startWorkflow(newAggregate);
      outbox.schedule(PayloadExtension.call(attached.getId(), "completed", null));
      return attached;
    });
    assertNotNull(aggregate);

    final var dispatched = extension.awaitDispatched(1, 20000);
    assertNull(dispatched.getFirst().payloadReference());
    assertEquals(before, countPayloads());

  }

}
