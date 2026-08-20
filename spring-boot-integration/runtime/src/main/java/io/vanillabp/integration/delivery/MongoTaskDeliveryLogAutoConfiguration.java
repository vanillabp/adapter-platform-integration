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
 * The records live in the collection
 * {@value MongoTaskDeliveryLog#DEFAULT_COLLECTION_NAME} and are keyed by the delivery
 * key, so uniqueness comes from the document ID and no unique index is needed. Unless
 * <code>vanillabp.outbox.create-schema</code> is disabled, an index on the record's
 * timestamp is created for the retention cleanup
 * (<code>vanillabp.outbox.retention</code>).
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
   * @param mongoTemplate The template writing the records within the current transaction
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree carrying the
   *          <code>vanillabp.outbox</code> section
   * @return The {@link TaskDeliveryLog} used for MongoDB-persisted aggregates
   */
  @Bean(name = DEFAULT_DELIVERY_LOG_BEAN_NAME, destroyMethod = "stop")
  public MongoTaskDeliveryLog vanillaBpMongoTaskDeliveryLog(
      final MongoTemplate mongoTemplate,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    return new MongoTaskDeliveryLog(
        mongoTemplate, MongoTaskDeliveryLog.DEFAULT_COLLECTION_NAME, vanillaBpProperties
            .getOutbox()
            .getRetention());

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
        // the retention deletes by the moment a record was last seen (story 97), so that is
        // the field the cleanup scans
        mongoTemplate
            .indexOps(MongoTaskDeliveryLog.DEFAULT_COLLECTION_NAME)
            .createIndex(new Index()
                .on("lastSeenAt", Sort.Direction.ASC));
      }
      deliveryLog.start();
    };

  }

}
