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
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.mongo.MongoIndexes;
import io.vanillabp.integration.outbox.jdbc.JdbcPhaseTwoOutboxAutoConfiguration;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-configuration of the default {@link PhaseTwoOutbox} for aggregates persisted in
 * MongoDB. It is active whenever Spring Data MongoDB is on the classpath and a
 * {@link MongoDatabaseFactory} is available. It lives SIDE BY SIDE with the JPA default
 * ({@link JdbcPhaseTwoOutboxAutoConfiguration}, or whichever store took the place of that
 * default). Each workflow aggregate is served by the outbox which matches its
 * persistence. The choice is made per aggregate, see
 * {@link io.vanillabp.integration.spi.PhaseTwoOutboxAware}. So outbox entries always ride
 * the aggregate's own transaction, also in applications with mixed persistence. Switch it
 * off with <code>vanillabp.outbox.mongo.enabled</code> if you do not want the default,
 * with its collection and its background dispatcher.
 * <p>
 * Unless <code>vanillabp.outbox.create-schema</code> is set to <code>false</code>, the
 * indexes of {@link MongoSchema#OUTBOX_INDEXES} and {@link MongoSchema#PAYLOAD_INDEXES}
 * are created at startup. Among them is the unique one over <code>dedupKey</code>. It is
 * the deduplication of the outbox contract, at the storage level. Where the application
 * manages its schema itself, the startup reads what the collections carry instead. It
 * names every index which is missing, with the statement which creates it.
 * <p>
 * <strong>Note:</strong> To take part in a transaction, outbox entries need MongoDB
 * transactions. That means a replica set and a <code>MongoTransactionManager</code>
 * bean. Otherwise scheduling is best-effort (see {@link MongoPhaseTwoOutbox}).
 */
@AutoConfiguration(
    after = JdbcPhaseTwoOutboxAutoConfiguration.class,
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
   * The name of the default MongoDB outbox bean. When several outbox beans exist, the
   * resolver uses it to assign aggregates persisted in MongoDB to THE default.
   */
  public static final String DEFAULT_OUTBOX_BEAN_NAME = "vanillaBpMongoPhaseTwoOutbox";

  /**
   * Built by Spring Boot while it applies its auto-configurations, and only where the
   * conditions above hold. Nothing in VanillaBP builds it.
   */
  public MongoPhaseTwoOutboxAutoConfiguration() {
  }

  /**
   * What polls the outbox collection. It claims a due entry, hands it to the router and
   * marks it done. It gives a failed entry its next attempt, and it deletes what the
   * retention released. It also carries the payloads of the entries.
   *
   * @param mongoTemplate The template used to claim and update entries
   * @param phaseTwoRouter Provider of the core's router dispatched to
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree with the
   *          <code>vanillabp.outbox</code> section. It is registered here as well, so the
   *          outbox also works in contexts without the full VanillaBP auto-configuration
   * @param metrics Provider of what a blocked entry is counted into. Micrometer is
   *          optional, so the bean may be absent
   * @return The dispatcher polling the outbox collection. It polls on a thread of its own
   *         and dispatches on its own lanes (<code>vanillabp.outbox.dispatch-threads</code>).
   *         No {@link org.springframework.scheduling.TaskScheduler} is involved
   */
  @Bean
  public MongoPhaseTwoOutboxDispatcher vanillaBpMongoPhaseTwoOutboxDispatcher(
      final MongoTemplate mongoTemplate,
      final ObjectProvider<PhaseTwoRouter> phaseTwoRouter,
      final VanillaBpConfigurationProperties vanillaBpProperties,
      final ObjectProvider<VanillaBpMetrics> metrics) {

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
   * <code>vanillabp.outbox.mongo.enabled</code> is not <code>false</code>. The JDBC outbox
   * beside it serves an aggregate which lives in a relational database.
   *
   * @param mongoTemplate The template used to write entries within the current transaction
   * @param dispatcher The dispatcher triggered right after a commit
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree with the
   *          <code>vanillabp.outbox</code> section. It is registered here as well, so the
   *          outbox also works in contexts without the full VanillaBP auto-configuration
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
    // what each index is read by is described once, in the core, because the Quarkus
    // extension creates the same ones. For the one over the payload references, see
    // decision 62 in the repository's DECISIONS.md
    if (vanillaBpProperties.getOutbox().isCreateSchema()) {
      MongoIndexes.createOn(mongoTemplate, collection, MongoSchema.OUTBOX_INDEXES);
      MongoIndexes.createOn(mongoTemplate, payloadCollection, MongoSchema.PAYLOAD_INDEXES);
      dropLegacyIdempotencyKeyIndex(mongoTemplate, collection);
    } else {
      // the collections themselves need no check, because MongoDB creates one with the
      // first document. So an application which manages its own schema only owes the
      // indexes
      MongoIndexes.reportMissingOn(mongoTemplate, collection, MongoSchema.OUTBOX_INDEXES);
      MongoIndexes.reportMissingOn(mongoTemplate, payloadCollection, MongoSchema.PAYLOAD_INDEXES);
    }
    return new MongoPhaseTwoOutbox(mongoTemplate, dispatcher, collection);

  }

  /**
   * Removes the sparse unique index over <code>idempotencyKey</code> which earlier
   * versions created. It also deduplicated dispatched entries, and this store stopped
   * doing that. An index which is not there any more is not an error.
   */
  private static void dropLegacyIdempotencyKeyIndex(
      final MongoTemplate mongoTemplate,
      final String collection) {

    try {
      mongoTemplate
          .indexOps(collection)
          .dropIndex("idempotencyKey");
    } catch (final RuntimeException e) {
      // usually the index is not there
      log.debug("No legacy unique index over 'idempotencyKey' to drop", e);
    }

  }

}
