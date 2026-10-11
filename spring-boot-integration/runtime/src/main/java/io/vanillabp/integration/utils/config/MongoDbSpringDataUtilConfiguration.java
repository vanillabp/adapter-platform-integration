package io.vanillabp.integration.utils.config;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;

import io.vanillabp.integration.utils.impl.MongoDbSpringDataUtil;

/**
 * Configuration of the MongoDB-based
 * {@link io.vanillabp.integration.utils.SpringDataUtil}. Usually
 * {@link MongoDbSpringDataUtilAutoConfiguration} activates it once a
 * {@link MongoDatabaseFactory} is available. Import this configuration yourself
 * ({@code @Import(MongoDbSpringDataUtilConfiguration.class)}) to change the default
 * order. An example is an application which uses both JPA and MongoDB and wants
 * MongoDB for aggregate persistence.
 */
@Configuration
public class MongoDbSpringDataUtilConfiguration {

  /**
   * Built by Spring wherever this configuration is imported: by
   * {@link MongoDbSpringDataUtilAutoConfiguration}, or by an application which imports it
   * itself to win against JPA.
   */
  public MongoDbSpringDataUtilConfiguration() {
  }

  /**
   * What VanillaBP uses to load and save a workflow aggregate which is a MongoDB document.
   *
   * @param applicationContext Where the aggregates' repositories are looked up, once per
   *          aggregate type, and cached afterwards
   * @param mongoDbFactory Only read where the application brings no converter. Then the
   *          default converter is built from it
   * @param mongoConverter What maps a document to its class and says which property holds
   *          the id. <code>null</code> where the application defines none, and the
   *          <code>MongoTemplate</code> default is built instead
   * @return The MongoDB implementation
   */
  @Bean
  public MongoDbSpringDataUtil mongoDbSpringDataUtil(
      final ApplicationContext applicationContext,
      final MongoDatabaseFactory mongoDbFactory,
      @Nullable final MongoConverter mongoConverter) {

    return new MongoDbSpringDataUtil(applicationContext, mongoDbFactory, mongoConverter);

  }

}
