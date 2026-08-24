package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.ConnectionString;

import io.vanillabp.integration.delivery.MongoTaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The compensating delete of the MongoDB delivery log on Spring Boot - what its Quarkus
 * twin does as well.
 * <p>
 * The record has to disappear when the transaction it was written in does not commit.
 * Where a MongoDB transaction covers the write (a replica set plus a
 * <code>MongoTransactionManager</code>, which this application has), the rollback removes
 * the document anyway; the compensation is what carries the case in which it does not,
 * and it must not make things worse where it does. Both are pinned here, the second one by
 * a transaction which is synchronized but writes nothing MongoDB would roll back.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = {
    TestApplication.class, MongoDeliveryLogCompensationTest.CompensationTestConfiguration.class
})
@Testcontainers
public class MongoDeliveryLogCompensationTest {

  private static final String DELIVERY_COLLECTION = "vanillabp-task-deliveries";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse("mongo:5.0"))
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  @TestConfiguration
  static class CompensationTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {
      return builder -> builder.applyConnectionString(
          new ConnectionString(mongoDb.getReplicaSetUrl()));
    }

  }

  @Autowired
  private MongoTaskDeliveryLog deliveryLog;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @BeforeEach
  public void emptyTheStore() {

    mongoTemplate.getCollection(DELIVERY_COLLECTION).deleteMany(new org.bson.Document());

  }

  private TaskDelivery delivery(
      final String key) {

    return new TaskDelivery(
        key, "test-adapter", "test-module", "SampleWorkflowService", "4711", "someTask", "COMPLETED", null, null, java.time.Instant
            .now());

  }

  private long records() {

    return mongoTemplate.getCollection(DELIVERY_COLLECTION).countDocuments();

  }

  @Test
  @DisplayName("A record written in a rolled-back transaction is gone afterwards")
  public void aRolledBackRecordDisappears() {

    assertThrowsExactly(
        RuntimeException.class,
        () -> transactionTemplate.execute(status -> {
          assertTrue(
              TransactionSynchronizationManager.isSynchronizationActive(),
              "without synchronization the compensation cannot register");
          deliveryLog.record(delivery("rolled-back-delivery"));
          throw new RuntimeException("no commit for this one");
        }));

    assertEquals(
        0,
        records(),
        "the delivery record survived the rollback - a repeated delivery would be skipped "
            + "although nothing was persisted");

  }

  @Test
  @DisplayName("A committed record stays, and the compensation does not touch it")
  public void aCommittedRecordStays() {

    transactionTemplate.execute(status -> deliveryLog.record(delivery("committed-delivery")));

    assertEquals(1, records());
    assertTrue(deliveryLog.recordedDelivery("committed-delivery").isPresent());

  }

  @Test
  @DisplayName("Without any transaction the record is written immediately, as before")
  public void withoutATransactionTheRecordIsWrittenRightAway() {

    deliveryLog.record(delivery("no-transaction-delivery"));

    assertEquals(1, records());

  }

}
