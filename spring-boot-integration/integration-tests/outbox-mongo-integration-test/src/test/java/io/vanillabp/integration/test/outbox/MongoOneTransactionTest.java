package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;

import io.vanillabp.integration.delivery.MongoTaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * Acceptance test on Spring Boot: an application storing EVERYTHING in MongoDB -
 * the aggregate in a MongoDB repository, the phase-two outbox and the log of processed task
 * deliveries in VanillaBP's MongoDB defaults - with no data source anywhere.
 * <p>
 * What is pinned here are the two conditions of that setup, which nothing said before this
 * story: the application defines a <code>MongoTransactionManager</code> (see
 * {@link TestApplication}, Spring Boot auto-configures none) and the deployment is a replica
 * set. Under both, all three writes really share one MongoDB transaction - a reader outside
 * it sees nothing while it runs, and everything after the commit.
 * <p>
 * The delivery record is written by VanillaBP inside its own transaction, so this test writes
 * one itself: what matters is that the store rides the transaction it is called in, which is
 * what {@link TaskDeliveryLog#record} demands.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = {
    TestApplication.class, MongoOneTransactionTest.OneTransactionTestConfiguration.class
})
@Testcontainers
public class MongoOneTransactionTest {

  private static final String OUTBOX_COLLECTION = "vanillabp-phase-two-outbox";

  private static final String DELIVERY_COLLECTION = "vanillabp-task-deliveries";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse("mongo:5.0"))
      // MongoDB transactions require a replica set
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  @TestConfiguration
  static class OneTransactionTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {
      return builder -> builder.applyConnectionString(
          new ConnectionString(mongoDb.getReplicaSetUrl()));
    }

  }

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private MongoTaskDeliveryLog deliveryLog;

  @Autowired
  private MongoClient mongoClient;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @BeforeEach
  public void emptyTheStores() {

    listener.reset();
    mongoTemplate.getCollection(OUTBOX_COLLECTION).deleteMany(new org.bson.Document());
    mongoTemplate.getCollection(DELIVERY_COLLECTION).deleteMany(new org.bson.Document());
    mongoTemplate.getCollection("outbox-test-aggregate").deleteMany(new org.bson.Document());

  }

  /**
   * Counts through a connection which is NOT part of the running transaction - the only way
   * to tell "written immediately" from "written in a transaction".
   */
  private long visibleOutside(
      final String collection) {

    return mongoClient
        .getDatabase(mongoTemplate.getDb().getName())
        .getCollection(collection)
        .countDocuments();

  }

  @Test
  @DisplayName("Aggregate, outbox entry and delivery record share one MongoDB transaction")
  public void allThreeStoresShareOneTransaction() {

    final var attached = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("one-transaction");
      final var started = processService.startWorkflow(aggregate);
      deliveryLog
          .record(
              new TaskDelivery(
                  "test-module|SampleWorkflowService|one-transaction|job-1", "test-adapter", "test-module", "SampleWorkflowService", started
                      .getId(), "someTask", null, "COMPLETED", null, null, java.time.Instant.now(), null));

      // still inside the transaction: nothing of this is visible to anybody else
      assertEquals(0, visibleOutside("outbox-test-aggregate"), "the aggregate was written before the commit");
      assertEquals(0, visibleOutside(OUTBOX_COLLECTION), "the outbox entry was written before the commit");
      assertEquals(0, visibleOutside(DELIVERY_COLLECTION), "the delivery record was written before the commit");
      return started;
    });

    assertNotNull(attached);
    assertEquals(1, visibleOutside("outbox-test-aggregate"));
    assertEquals(1, visibleOutside(OUTBOX_COLLECTION));
    assertEquals(1, visibleOutside(DELIVERY_COLLECTION));

  }

  @Test
  @DisplayName("A rollback takes all three writes with it")
  public void rollbackRemovesAllThree() {

    assertThrowsExactly(
        RuntimeException.class,
        () -> transactionTemplate.execute(status -> {
          final var aggregate = new Aggregate();
          aggregate.setContent("rolled-back");
          final var started = processService.startWorkflow(aggregate);
          deliveryLog
              .record(
                  new TaskDelivery(
                      "test-module|SampleWorkflowService|rolled-back|job-1", "test-adapter", "test-module", "SampleWorkflowService", started
                          .getId(), "someTask", null, "COMPLETED", null, null, java.time.Instant.now(), null));
          throw new RuntimeException("no commit for this one");
        }));

    assertEquals(0, visibleOutside("outbox-test-aggregate"), "the aggregate survived the rollback");
    assertEquals(0, visibleOutside(OUTBOX_COLLECTION), "the outbox entry survived the rollback");
    assertEquals(0, visibleOutside(DELIVERY_COLLECTION), "the delivery record survived the rollback");

  }

}
