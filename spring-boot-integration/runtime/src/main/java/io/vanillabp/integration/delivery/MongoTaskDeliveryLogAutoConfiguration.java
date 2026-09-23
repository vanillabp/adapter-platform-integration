package io.vanillabp.integration.delivery;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.repository.MongoRepository;

import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.spi.TaskDeliveryLog;

/**
 * Auto-configuration of the default {@link TaskDeliveryLog} for MongoDB-based aggregate
 * persistence, coexisting with the JDBC default
 * ({@link JdbcTaskDeliveryLogAutoConfiguration}) - each workflow aggregate is served by
 * the log matching its persistence.
 * <p>
 * The records live in the collection <code>vanillabp.outbox.mongo.delivery-collection</code>
 * names and are keyed by the delivery key, so uniqueness comes from the document ID and no
 * unique index is needed. Unless
 * <code>vanillabp.outbox.create-schema</code> is disabled, two indexes are created: one on
 * the record's timestamp for the retention cleanup
 * (<code>vanillabp.delivery.retention</code>, falling back to
 * <code>vanillabp.outbox.retention</code>), and one on the task id, which is what the BPMS
 * election of a task operation reads a record by.
 */
@AutoConfiguration(
    after = JdbcTaskDeliveryLogAutoConfiguration.class,
    afterName = "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration")
@ConditionalOnClass(MongoRepository.class)
@ConditionalOnBean({
    MongoDatabaseFactory.class, MongoTemplate.class
})
@ConditionalOnBooleanProperty(name = "vanillabp.outbox.mongo.enabled", matchIfMissing = true)
@EnableConfigurationProperties(VanillaBpConfigurationProperties.class)
public class MongoTaskDeliveryLogAutoConfiguration {

  /**
   * The name of the default MongoDB delivery-log bean - used by the resolver to
   * attribute MongoDB-persisted aggregates to THE default when several log beans exist.
   */
  public static final String DEFAULT_DELIVERY_LOG_BEAN_NAME = "vanillaBpMongoTaskDeliveryLog";

  /**
   * The collection the records go into: what
   * <code>vanillabp.outbox.mongo.delivery-collection</code> says. The indexes are created
   * on the same name the log writes to, which is why both beans below ask this method
   * instead of remembering a name of their own.
   *
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree
   * @return The name of the delivery-log collection
   */
  private static String collectionOf(
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    return vanillaBpProperties
        .getOutbox()
        .getMongo()
        .getDeliveryCollection();

  }

  /**
   * @param mongoTemplate The template writing the records within the current transaction
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree, asked for the
   *          retention of delivery records (<code>vanillabp.delivery.retention</code>,
   *          falling back to <code>vanillabp.outbox.retention</code>)
   * @return The {@link TaskDeliveryLog} used for MongoDB-persisted aggregates
   */
  @Bean(name = DEFAULT_DELIVERY_LOG_BEAN_NAME, destroyMethod = "stop")
  public MongoTaskDeliveryLog vanillaBpMongoTaskDeliveryLog(
      final MongoTemplate mongoTemplate,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    return new MongoTaskDeliveryLog(
        mongoTemplate, collectionOf(vanillaBpProperties), vanillaBpProperties
            .resolvedDeliveryRetention());

  }

  /**
   * Creates the index the cleanup reads and starts the cleanup once all singletons
   * exist.
   *
   * @param mongoTemplate The template creating the index
   * @param deliveryLog The delivery log to start
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree
   * @return The startup hook
   */
  @Bean
  public SmartInitializingSingleton vanillaBpMongoTaskDeliveryLogStartup(
      final MongoTemplate mongoTemplate,
      final MongoTaskDeliveryLog deliveryLog,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    return () -> {
      if (vanillaBpProperties.getOutbox().isCreateSchema()) {
        // the retention deletes by the moment a record was last seen, so that is
        // the field the cleanup scans; MongoDB answers a createIndex of an index which is
        // already there with its name, so two instances starting together do not collide
        mongoTemplate
            .indexOps(collectionOf(vanillaBpProperties))
            .createIndex(new Index()
                .on("lastSeenAt", Sort.Direction.ASC));
        // the election of a task operation looks a record up by the task the caller
        // names, once per operation - without this index that read is a collection scan
        // and costs more than the BPMS round trip it saves
        mongoTemplate
            .indexOps(collectionOf(vanillaBpProperties))
            .createIndex(new Index()
                .on("taskId", Sort.Direction.ASC));
        // an extension asks for the open tasks of one workflow aggregate once per screen
        // it builds, and MongoDB knows no key-length limit, so the aggregate id itself is
        // the index here - unlike in the SQL table, whose column is too wide for one
        mongoTemplate
            .indexOps(collectionOf(vanillaBpProperties))
            .createIndex(new Index()
                .on("aggregateId", Sort.Direction.ASC));
        // the core asks for the open tasks of ONE workflow of the BPMS on every wake-up
        // of that workflow, which is far more often than an extension builds a screen
        mongoTemplate
            .indexOps(collectionOf(vanillaBpProperties))
            .createIndex(new Index()
                .on("workflowId", Sort.Direction.ASC));
      }
      deliveryLog.start();
    };

  }

}
