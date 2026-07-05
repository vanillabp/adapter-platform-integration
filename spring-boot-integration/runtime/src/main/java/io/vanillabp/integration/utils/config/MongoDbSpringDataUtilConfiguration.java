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
 * {@link io.vanillabp.integration.utils.SpringDataUtil}. Usually it is activated by
 * {@link MongoDbSpringDataUtilAutoConfiguration} once a
 * {@link MongoDatabaseFactory} is available. Import this configuration explicitly
 * ({@code @Import(MongoDbSpringDataUtilConfiguration.class)}) to override the
 * default precedence, e.g. to force MongoDB-based aggregate persistence in an
 * application using both JPA and MongoDB.
 */
@Configuration
public class MongoDbSpringDataUtilConfiguration {

  @Bean
  public MongoDbSpringDataUtil mongoDbSpringDataUtil(
      final ApplicationContext applicationContext,
      final MongoDatabaseFactory mongoDbFactory,
      @Nullable final MongoConverter mongoConverter) {

    return new MongoDbSpringDataUtil(applicationContext, mongoDbFactory, mongoConverter);

  }

}
