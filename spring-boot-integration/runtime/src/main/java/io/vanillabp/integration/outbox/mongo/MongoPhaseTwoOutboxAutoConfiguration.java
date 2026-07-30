package io.vanillabp.integration.outbox.mongo;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.repository.MongoRepository;

import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.outbox.gruelbox.GruelboxPhaseTwoOutboxAutoConfiguration;

/**
 * Auto-configuration of the default {@link PhaseTwoOutbox} for MongoDB-based aggregate
 * persistence. Only active under the same conditions as the MongoDB
 * {@link io.vanillabp.integration.utils.SpringDataUtil} (Spring Data MongoDB on the
 * classpath, a {@link MongoDatabaseFactory} available) and if no other
 * {@link PhaseTwoOutbox} bean was defined. It is ordered after
 * {@link GruelboxPhaseTwoOutboxAutoConfiguration}: if both JPA and MongoDB are
 * configured, JPA wins deterministically.
 * <p>
 * Unless <code>vanillabp.outbox.create-schema</code> is set to <code>false</code>, a
 * sparse unique index on the entries' idempotency key is created automatically - the
 * storage-level deduplication of the outbox contract. If the schema is managed
 * manually, create that index yourself (see the module's <code>README.md</code>).
 * <p>
 * <strong>Note:</strong> Transactional enlisting of outbox entries requires MongoDB
 * transactions, i.e. a replica set and a
 * <code>MongoTransactionManager</code> bean - otherwise scheduling is best-effort
 * (see {@link MongoPhaseTwoOutbox}).
 */
@AutoConfiguration(
    after = GruelboxPhaseTwoOutboxAutoConfiguration.class,
    afterName = "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration")
@ConditionalOnClass(MongoRepository.class)
@ConditionalOnBean({
    MongoDatabaseFactory.class, MongoTemplate.class
})
@ConditionalOnMissingBean(PhaseTwoOutbox.class)
@EnableConfigurationProperties(VanillaBpConfigurationProperties.class)
public class MongoPhaseTwoOutboxAutoConfiguration {

  /**
   * @param mongoTemplate The template used to claim and update entries
   * @param phaseTwoRouter Provider of the core's router dispatched to
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree carrying the
   *          <code>vanillabp.outbox</code> section (registered here as well so the
   *          outbox works in contexts without the full VanillaBP auto-configuration)
   * @return The dispatcher polling the outbox collection (private single-thread
   *         executor - no {@link org.springframework.scheduling.TaskScheduler}
   *         involved)
   */
  @Bean
  public MongoPhaseTwoOutboxDispatcher vanillaBpMongoPhaseTwoOutboxDispatcher(
      final MongoTemplate mongoTemplate,
      final ObjectProvider<PhaseTwoRouter> phaseTwoRouter,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    return new MongoPhaseTwoOutboxDispatcher(
        mongoTemplate, phaseTwoRouter, vanillaBpProperties.getOutbox());

  }

  /**
   * @param mongoTemplate The template used to write entries within the current transaction
   * @param dispatcher The dispatcher triggered right after a commit
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree carrying the
   *          <code>vanillabp.outbox</code> section (registered here as well so the
   *          outbox works in contexts without the full VanillaBP auto-configuration)
   * @return The {@link PhaseTwoOutbox} used by the process services
   */
  @Bean
  public MongoPhaseTwoOutbox vanillaBpMongoPhaseTwoOutbox(
      final MongoTemplate mongoTemplate,
      final MongoPhaseTwoOutboxDispatcher dispatcher,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    if (vanillaBpProperties.getOutbox().isCreateSchema()) {
      mongoTemplate
          .indexOps(MongoPhaseTwoOutbox.COLLECTION)
          .createIndex(new Index()
              .on("idempotencyKey", Sort.Direction.ASC)
              .unique()
              .sparse());
    }
    return new MongoPhaseTwoOutbox(mongoTemplate, dispatcher);

  }

}
