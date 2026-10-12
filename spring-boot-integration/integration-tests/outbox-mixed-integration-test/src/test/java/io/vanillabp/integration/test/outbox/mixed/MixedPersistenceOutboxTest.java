package io.vanillabp.integration.test.outbox.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
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

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties.MongoOutboxProperties;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.spi.process.ProcessService;

/**
 * Acceptance test of how the outbox is chosen per aggregate. The application has MIXED
 * persistence (JPA/H2 and a MongoDB replica set from Testcontainers) and its own outbox
 * for one "hot" process:
 * <ul>
 * <li>the outbox entry of the JPA aggregate lands in the JDBC outbox, inside the JPA
 * transaction (a rollback removes it),</li>
 * <li>the outbox entry of the MongoDB aggregate lands in the MongoDB outbox, inside the
 * MongoDB transaction (a rollback removes it),</li>
 * <li>the entry of the "hot" aggregate lands only in ITS own table
 * ({@code HOT_OUTBOX}, assigned by a {@code PhaseTwoOutboxAware} bean),</li>
 * <li>each aggregate belongs to exactly ONE outbox. Its entries are never split across
 * stores, so the order per aggregate holds by design, and</li>
 * <li>phase two is dispatched from every store, with the aggregate ID converted back to
 * its original type.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = {
    TestApplication.class, MixedPersistenceOutboxTest.MongoConnectionTestConfiguration.class
})
@Testcontainers
public class MixedPersistenceOutboxTest {

  /**
   * How long a test waits before it says that nothing more happened. The application
   * dispatches every <code>vanillabp.outbox.attempt-frequency</code>. These tests set it
   * to half a second, so this is three of those windows.
   * <p>
   * It is a guard, not a measurement of speed. A busy machine which leaves this JVM
   * without a turn only makes the wait longer. And the test then asserts a count which did
   * not grow.
   */
  private static final long UNTIL_NOTHING_MORE_CAN_COME = 1500;

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB))
      // MongoDB transactions require a replica set
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  @TestConfiguration
  static class MongoConnectionTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {
      return builder -> builder.applyConnectionString(
          new ConnectionString(mongoDb.getReplicaSetUrl()));
    }

  }

  @Autowired
  private ProcessService<JpaAggregate> jpaProcessService;

  @Autowired
  private ProcessService<MongoAggregate> mongoProcessService;

  @Autowired
  private ProcessService<HotAggregate> hotProcessService;

  @Autowired
  @Qualifier("jpaTransactionTemplate")
  private TransactionTemplate jpaTransactionTemplate;

  @Autowired
  @Qualifier("mongoTransactionTemplate")
  private TransactionTemplate mongoTransactionTemplate;

  @Autowired
  private DataSource dataSource;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @BeforeEach
  public void resetListener() {

    listener.reset();

  }

  private long countJdbcEntries() {

    return PhaseTwoOutboxReader
        .ofTheVanillaBpOutbox(dataSource)
        .entries()
        .size();

  }

  private long countHotEntries() {

    return PhaseTwoOutboxReader
        .ofTheVanillaBpOutbox(dataSource, TestApplication.HOT_OUTBOX_TABLE, TestApplication.HOT_PAYLOAD_TABLE)
        .entries()
        .size();

  }

  private long countMongoEntries() {

    return mongoTemplate
        .getCollection(MongoOutboxProperties.DEFAULT_COLLECTION)
        .countDocuments();

  }

  @Test
  @DisplayName("The JPA aggregate's entry lands in the JDBC outbox only and phase two is dispatched")
  public void jpaAggregateUsesJdbcOutbox() throws Exception {

    final var jdbcBefore = countJdbcEntries();
    final var mongoBefore = countMongoEntries();
    final var hotBefore = countHotEntries();

    final var attachedAggregate = jpaTransactionTemplate.execute(status -> {
      final var aggregate = new JpaAggregate();
      aggregate.setContent("jpa-commit-test");
      return jpaProcessService.startWorkflow(aggregate);
    });
    assertNotNull(attachedAggregate);
    assertNotNull(attachedAggregate.getId());

    // phase two is dispatched with the ID converted back to Long
    final var invocations = listener.awaitInvocations(1, 10000);
    assertEquals(attachedAggregate.getId(), invocations.getFirst());

    // the entry went only to the JDBC store. It never went to the MongoDB collection or
    // the own store of the hot process (each aggregate belongs to exactly one outbox)
    assertEquals(jdbcBefore + 1, countJdbcEntries());
    assertEquals(mongoBefore, countMongoEntries());
    assertEquals(hotBefore, countHotEntries());

  }

  @Test
  @DisplayName("The MongoDB aggregate's entry lands in the MongoDB outbox only and phase two is dispatched")
  public void mongoAggregateUsesMongoOutbox() throws Exception {

    final var jdbcBefore = countJdbcEntries();
    final var mongoBefore = countMongoEntries();
    final var hotBefore = countHotEntries();

    final var attachedAggregate = mongoTransactionTemplate.execute(status -> {
      final var aggregate = new MongoAggregate();
      aggregate.setContent("mongo-commit-test");
      return mongoProcessService.startWorkflow(aggregate);
    });
    assertNotNull(attachedAggregate);
    assertNotNull(attachedAggregate.getId());

    // phase two is dispatched with the String ID of the MongoDB aggregate
    final var invocations = listener.awaitInvocations(1, 10000);
    assertEquals(attachedAggregate.getId(), invocations.getFirst());

    assertEquals(mongoBefore + 1, countMongoEntries());
    assertEquals(jdbcBefore, countJdbcEntries());
    assertEquals(hotBefore, countHotEntries());

  }

  @Test
  @DisplayName("The hot aggregate's entry lands in its dedicated store only and phase two is dispatched")
  public void hotAggregateUsesDedicatedOutbox() throws Exception {

    final var jdbcBefore = countJdbcEntries();
    final var mongoBefore = countMongoEntries();
    final var hotBefore = countHotEntries();

    final var attachedAggregate = jpaTransactionTemplate.execute(status -> {
      final var aggregate = new HotAggregate();
      aggregate.setContent("hot-commit-test");
      return hotProcessService.startWorkflow(aggregate);
    });
    assertNotNull(attachedAggregate);
    assertNotNull(attachedAggregate.getId());

    final var invocations = listener.awaitInvocations(1, 10000);
    assertEquals(attachedAggregate.getId(), invocations.getFirst());

    // the PhaseTwoOutboxAware bean sent the entry to the own store of the hot process.
    // The default stores stay untouched, so the hot process carries its load alone
    assertEquals(hotBefore + 1, countHotEntries());
    assertEquals(jdbcBefore, countJdbcEntries());
    assertEquals(mongoBefore, countMongoEntries());

  }

  @Test
  @DisplayName("A rolled-back JPA transaction leaves no outbox entry and no phase two")
  public void jpaRollbackLeavesNoEntryAndNoPhaseTwo() throws Exception {

    final var jdbcBefore = countJdbcEntries();

    final var exception = assertThrowsExactly(
        RuntimeException.class,
        () -> jpaTransactionTemplate.execute(status -> {
          final var aggregate = new JpaAggregate();
          aggregate.setContent("jpa-rollback-test");
          jpaProcessService.startWorkflow(aggregate);
          throw new RuntimeException("test rollback");
        }));
    assertEquals("test rollback", exception.getMessage());

    // the entry was part of the JPA transaction which was rolled back
    assertEquals(jdbcBefore, countJdbcEntries());

    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertTrue(listener.getInvocations().isEmpty());

  }

  @Test
  @DisplayName("A rolled-back MongoDB transaction leaves no outbox entry and no phase two")
  public void mongoRollbackLeavesNoEntryAndNoPhaseTwo() throws Exception {

    final var mongoBefore = countMongoEntries();

    final var exception = assertThrowsExactly(
        RuntimeException.class,
        () -> mongoTransactionTemplate.execute(status -> {
          final var aggregate = new MongoAggregate();
          aggregate.setContent("mongo-rollback-test");
          mongoProcessService.startWorkflow(aggregate);
          throw new RuntimeException("test rollback");
        }));
    assertEquals("test rollback", exception.getMessage());

    // the entry was part of the MongoDB transaction which was rolled back
    assertEquals(mongoBefore, countMongoEntries());

    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertTrue(listener.getInvocations().isEmpty());

  }

}
