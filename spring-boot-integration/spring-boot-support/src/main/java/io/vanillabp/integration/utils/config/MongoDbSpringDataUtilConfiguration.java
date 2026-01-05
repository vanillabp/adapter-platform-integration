package io.vanillabp.integration.utils.config;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.lang.Nullable;

import io.vanillabp.integration.utils.impl.MongoDbSpringDataUtil;

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
