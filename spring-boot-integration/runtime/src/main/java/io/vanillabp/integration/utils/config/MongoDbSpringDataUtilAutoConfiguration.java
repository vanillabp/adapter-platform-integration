package io.vanillabp.integration.utils.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.repository.MongoRepository;

import io.vanillabp.integration.utils.SpringDataUtil;

/**
 * Auto-configuration of the MongoDB-based {@link SpringDataUtil} (see
 * {@link MongoDbSpringDataUtilConfiguration}). It is only active if
 * <ul>
 *   <li>Spring Data MongoDB is on the classpath,</li>
 *   <li>a {@link MongoDatabaseFactory} is available and</li>
 *   <li>no other {@link SpringDataUtil} bean was defined.</li>
 * </ul>
 * It runs after {@link JpaSpringDataUtilConfiguration}. So if both JPA and MongoDB are
 * configured, JPA always wins. To use MongoDB for aggregate persistence in that case,
 * import {@link MongoDbSpringDataUtilConfiguration} yourself.
 */
@AutoConfiguration(
    after = JpaSpringDataUtilConfiguration.class,
    afterName = "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration")
@ConditionalOnClass(MongoRepository.class)
@ConditionalOnBean(MongoDatabaseFactory.class)
@ConditionalOnMissingBean(SpringDataUtil.class)
@Import(MongoDbSpringDataUtilConfiguration.class)
public class MongoDbSpringDataUtilAutoConfiguration {

  /**
   * Built by Spring Boot while it applies its auto-configurations, and only where the
   * conditions above hold. It defines no bean itself. All it does is import
   * {@link MongoDbSpringDataUtilConfiguration}. So an application may import that one
   * directly and get the same bean without these conditions.
   */
  public MongoDbSpringDataUtilAutoConfiguration() {
  }

}
