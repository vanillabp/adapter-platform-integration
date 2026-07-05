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
 * {@link MongoDbSpringDataUtilConfiguration}). Only active if
 * <ul>
 *   <li>Spring Data MongoDB is on the classpath,</li>
 *   <li>a {@link MongoDatabaseFactory} is available and</li>
 *   <li>no other {@link SpringDataUtil} bean was defined.</li>
 * </ul>
 * It is ordered after {@link JpaSpringDataUtilConfiguration}: if both JPA and
 * MongoDB are configured, JPA wins deterministically. To force MongoDB-based
 * aggregate persistence in this situation import
 * {@link MongoDbSpringDataUtilConfiguration} explicitly.
 */
@AutoConfiguration(
    after = JpaSpringDataUtilConfiguration.class,
    afterName = "org.springframework.boot.data.mongodb.autoconfigure.DataMongoAutoConfiguration")
@ConditionalOnClass(MongoRepository.class)
@ConditionalOnBean(MongoDatabaseFactory.class)
@ConditionalOnMissingBean(SpringDataUtil.class)
@Import(MongoDbSpringDataUtilConfiguration.class)
public class MongoDbSpringDataUtilAutoConfiguration {

}
