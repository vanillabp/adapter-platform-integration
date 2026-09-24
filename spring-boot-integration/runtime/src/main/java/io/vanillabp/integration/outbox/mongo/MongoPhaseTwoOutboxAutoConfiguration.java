package io.vanillabp.integration.outbox.mongo;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;

import io.vanillabp.integration.adapter.migration.mongo.MongoSchema;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.mongo.MongoIndexes;
import io.vanillabp.integration.outbox.gruelbox.GruelboxPhaseTwoOutboxAutoConfiguration;
import io.vanillabp.integration.outbox.jdbc.JdbcPhaseTwoOutboxAutoConfiguration;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-configuration of the default {@link PhaseTwoOutbox} for MongoDB-based aggregate
 * persistence. Active whenever Spring Data MongoDB is on the classpath and a
 * {@link MongoDatabaseFactory} is available - it COEXISTS with the JPA default
 * ({@link JdbcPhaseTwoOutboxAutoConfiguration}, or
 * {@link GruelboxPhaseTwoOutboxAutoConfiguration} where the application still runs
 * gruelbox): each workflow aggregate
 * is served by the outbox matching its persistence (selection per aggregate, see
 * {@link io.vanillabp.integration.spi.PhaseTwoOutboxAware}), so outbox
 * entries always ride the aggregate's own transaction even in mixed-persistence
 * applications. Disable via <code>vanillabp.outbox.mongo.enabled</code> if the
 * default (including its collection and background dispatcher) is unwanted.
 * <p>
 * Unless <code>vanillabp.outbox.create-schema</code> is set to <code>false</code>, the
 * indexes of {@link MongoSchema#OUTBOX_INDEXES} and {@link MongoSchema#PAYLOAD_INDEXES}
 * are created at startup, the unique one over <code>dedupKey</code> among them: that is
 * the storage-level deduplication of the outbox contract. Where the application manages
 * its schema itself, the startup reads what the collections carry instead and names every
 * index which is missing, with the statement which creates it.
 * <p>
 * <strong>Note:</strong> Transactional enlisting of outbox entries requires MongoDB
 * transactions, i.e. a replica set and a
 * <code>MongoTransactionManager</code> bean - otherwise scheduling is best-effort
 * (see {@link MongoPhaseTwoOutbox}).
 */
@AutoConfiguration(
    after = {
        JdbcPhaseTwoOutboxAutoConfiguration.class, GruelboxPhaseTwoOutboxAutoConfiguration.class
    },
    afterName = "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration")
@ConditionalOnClass(MongoRepository.class)
@ConditionalOnBean({
    MongoDatabaseFactory.class, MongoTemplate.class
})
@ConditionalOnBooleanProperty(name = "vanillabp.outbox.mongo.enabled", matchIfMissing = true)
@EnableConfigurationProperties(VanillaBpConfigurationProperties.class)
@Slf4j
public class MongoPhaseTwoOutboxAutoConfiguration {

  /**
   * The name of the default MongoDB outbox bean - used by the resolver to attribute
   * MongoDB-persisted aggregates to THE default when several outbox beans exist.
   */
  public static final String DEFAULT_OUTBOX_BEAN_NAME = "vanillaBpMongoPhaseTwoOutbox";

  /**
   * Built by Spring Boot while it applies its auto-configurations, and only where the
   * conditions above hold. Nothing in VanillaBP builds it.
   */
  public MongoPhaseTwoOutboxAutoConfiguration() {
  }

  /**
   * What polls the outbox collection: it claims a due entry, hands it to the router and
   * marks it done, gives a failed one its next attempt, and deletes what the retention
   * released. It also carries the payloads of the entries.
   *
   * @param mongoTemplate The template used to claim and update entries
   * @param phaseTwoRouter Provider of the core's router dispatched to
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree carrying the
   *          <code>vanillabp.outbox</code> section (registered here as well so the
   *          outbox works in contexts without the full VanillaBP auto-configuration)
   * @param metrics Provider of what a blocked entry is counted into; Micrometer is
   *          optional, so the bean may legitimately be absent
   * @return The dispatcher polling the outbox collection (private single-thread
   *         executor - no {@link org.springframework.scheduling.TaskScheduler}
   *         involved)
   */
  @Bean
  public MongoPhaseTwoOutboxDispatcher vanillaBpMongoPhaseTwoOutboxDispatcher(
      final MongoTemplate mongoTemplate,
      final ObjectProvider<PhaseTwoRouter> phaseTwoRouter,
      final VanillaBpConfigurationProperties vanillaBpProperties,
      final ObjectProvider<io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics> metrics) {

    return new MongoPhaseTwoOutboxDispatcher(
        mongoTemplate, phaseTwoRouter, vanillaBpProperties.getOutbox(), vanillaBpProperties
            .getOutbox()
            .getMongo()
            .getCollection(), metrics);

  }

  /**
   * The phase-two outbox of every workflow aggregate this application persists in MongoDB.
   * Spring builds it where Spring Data MongoDB is on the classpath, a
   * {@link MongoDatabaseFactory} and a {@link MongoTemplate} exist and
   * <code>vanillabp.outbox.mongo.enabled</code> is not <code>false</code>; an aggregate
   * living in a relational database is served by the JDBC outbox beside it.
   *
   * @param mongoTemplate The template used to write entries within the current transaction
   * @param dispatcher The dispatcher triggered right after a commit
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree carrying the
   *          <code>vanillabp.outbox</code> section (registered here as well so the
   *          outbox works in contexts without the full VanillaBP auto-configuration)
   * @return The {@link PhaseTwoOutbox} used by the process services
   */
  @Bean(DEFAULT_OUTBOX_BEAN_NAME)
  public MongoPhaseTwoOutbox vanillaBpMongoPhaseTwoOutbox(
      final MongoTemplate mongoTemplate,
      final MongoPhaseTwoOutboxDispatcher dispatcher,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    final var collection = vanillaBpProperties
        .getOutbox()
        .getMongo()
        .getCollection();
    final var payloadCollection = vanillaBpProperties
        .getOutbox()
        .getMongo()
        .payloadCollectionName();
    // what each of them is read by is described once, in the core, because the Quarkus
    // extension creates the same ones - see decision 76 in the repository's DECISIONS.md
    // for the one over the payload references
    if (vanillaBpProperties.getOutbox().isCreateSchema()) {
      MongoIndexes.createOn(mongoTemplate, collection, MongoSchema.OUTBOX_INDEXES);
      MongoIndexes.createOn(mongoTemplate, payloadCollection, MongoSchema.PAYLOAD_INDEXES);
      dropLegacyIdempotencyKeyIndex(mongoTemplate, collection);
    } else {
      // the collections themselves need no check: MongoDB creates one with the first
      // document, so what an application managing its own schema owes are the indexes
      MongoIndexes.reportMissingOn(mongoTemplate, collection, MongoSchema.OUTBOX_INDEXES);
      MongoIndexes.reportMissingOn(mongoTemplate, payloadCollection, MongoSchema.PAYLOAD_INDEXES);
    }
    return new MongoPhaseTwoOutbox(mongoTemplate, dispatcher, collection);

  }

  /**
   * Removes the sparse unique index over <code>idempotencyKey</code> which earlier
   * versions created. It deduplicated dispatched entries as well, which is what this
   * store stopped doing; an index which is not there any more is not an error.
   */
  private static void dropLegacyIdempotencyKeyIndex(
      final MongoTemplate mongoTemplate,
      final String collection) {

    try {
      mongoTemplate
          .indexOps(collection)
          .dropIndex("idempotencyKey");
    } catch (final RuntimeException e) {
      // not there is the normal case
      log.debug("No legacy unique index over 'idempotencyKey' to drop", e);
    }

  }

}
