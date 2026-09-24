package io.vanillabp.integration.delivery;

import org.springframework.beans.factory.SmartInitializingSingleton;
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
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.mongo.MongoIndexes;
import io.vanillabp.integration.spi.TaskDeliveryLog;

/**
 * Auto-configuration of the default {@link TaskDeliveryLog} for MongoDB-based aggregate
 * persistence, coexisting with the JDBC default
 * ({@link JdbcTaskDeliveryLogAutoConfiguration}) - each workflow aggregate is served by
 * the log matching its persistence.
 * <p>
 * The records live in the collection <code>vanillabp.outbox.mongo.delivery-collection</code>
 * names and are keyed by the delivery key, so uniqueness comes from the document ID and no
 * unique index is needed. Unless <code>vanillabp.outbox.create-schema</code> is disabled,
 * the indexes of {@link MongoSchema#DELIVERY_INDEXES} are created at startup. Where the
 * application manages its schema itself, the startup reads what the collection carries
 * instead and names every index which is missing, with the statement which creates it.
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
   * Built by Spring Boot while it applies its auto-configurations, and only where the
   * conditions above hold. Nothing in VanillaBP builds it.
   */
  public MongoTaskDeliveryLogAutoConfiguration() {
  }

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
   * The delivery log of every workflow aggregate this application persists in MongoDB.
   * Spring builds it where Spring Data MongoDB is on the classpath, a
   * {@link MongoDatabaseFactory} and a {@link MongoTemplate} exist and
   * <code>vanillabp.outbox.mongo.enabled</code> is not <code>false</code>; an aggregate
   * living in a relational database is served by the other default beside it.
   *
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
      // what each of them is read by is described once, in the core, because the Quarkus
      // extension creates the same ones
      if (vanillaBpProperties.getOutbox().isCreateSchema()) {
        MongoIndexes
            .createOn(mongoTemplate, collectionOf(vanillaBpProperties), MongoSchema.DELIVERY_INDEXES);
      } else {
        // the collection itself needs no check: MongoDB creates one with the first
        // document, so what an application managing its own schema owes are the indexes
        MongoIndexes
            .reportMissingOn(mongoTemplate, collectionOf(vanillaBpProperties), MongoSchema.DELIVERY_INDEXES);
      }
      deliveryLog.start();
    };

  }

}
