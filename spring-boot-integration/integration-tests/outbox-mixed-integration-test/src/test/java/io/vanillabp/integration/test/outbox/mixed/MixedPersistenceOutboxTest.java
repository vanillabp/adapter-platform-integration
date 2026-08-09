package io.vanillabp.integration.test.outbox.mixed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.ConnectionString;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * Acceptance test of the per-aggregate outbox selection (story 26i) in a
 * MIXED-PERSISTENCE application (JPA/H2 + MongoDB/TestContainers replica set) with a
 * dedicated outbox for one "hot" process:
 * <ul>
 * <li>the JPA aggregate's outbox entry lands in the JDBC (gruelbox) outbox and rides
 * the JPA transaction (gone on rollback),</li>
 * <li>the MongoDB aggregate's outbox entry lands in the MongoDB outbox and rides the
 * MongoDB transaction (gone on rollback),</li>
 * <li>the "hot" aggregate's entry lands in ITS dedicated table only
 * ({@code HOT_OUTBOX}, assigned via a {@code PhaseTwoOutboxAware} bean),</li>
 * <li>each aggregate maps to exactly ONE outbox - entries never split across stores
 * (per-aggregate ordering is preserved by construction) - and</li>
 * <li>phase two is dispatched from every store with the aggregate ID converted back
 * to its original type.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = {
    TestApplication.class, MixedPersistenceOutboxTest.MongoConnectionTestConfiguration.class
})
@Testcontainers
public class MixedPersistenceOutboxTest {

  private static final String COUNT_JDBC_OUTBOX_ENTRIES = "select count(*) from TXNO_OUTBOX";

  private static final String COUNT_HOT_OUTBOX_ENTRIES = "select count(*) from HOT_OUTBOX";

  private static final String MONGO_OUTBOX_COLLECTION = "vanillabp-phase-two-outbox";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse("mongo:5.0"))
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
  private JdbcTemplate jdbcTemplate;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @BeforeEach
  public void resetListener() {

    listener.reset();

  }

  private long countJdbcEntries() {

    final var count = jdbcTemplate.queryForObject(COUNT_JDBC_OUTBOX_ENTRIES, Long.class);
    return count == null ? 0 : count;

  }

  private long countHotEntries() {

    final var count = jdbcTemplate.queryForObject(COUNT_HOT_OUTBOX_ENTRIES, Long.class);
    return count == null ? 0 : count;

  }

  private long countMongoEntries() {

    return mongoTemplate.getCollection(MONGO_OUTBOX_COLLECTION).countDocuments();

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

    // phase two dispatched with the ID converted back to Long
    final var invocations = listener.awaitInvocations(1, 10000);
    assertEquals(attachedAggregate.getId(), invocations.getFirst());

    // the entry went to the JDBC store only - never to the MongoDB collection or
    // the dedicated hot store (each aggregate maps to exactly one outbox)
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

    // phase two dispatched with the MongoDB aggregate's String ID
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

    // the PhaseTwoOutboxAware bean routed the entry to the dedicated store - the
    // default stores stay untouched (load isolation for the hot process)
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

    // the entry was enlisted in the rolled-back JPA transaction
    assertEquals(jdbcBefore, countJdbcEntries());

    Thread.sleep(1500);
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

    // the entry was enlisted in the rolled-back MongoDB transaction
    assertEquals(mongoBefore, countMongoEntries());

    Thread.sleep(1500);
    assertTrue(listener.getInvocations().isEmpty());

  }

}
