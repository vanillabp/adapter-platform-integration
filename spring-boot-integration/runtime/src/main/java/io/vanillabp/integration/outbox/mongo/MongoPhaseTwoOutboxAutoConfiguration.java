package io.vanillabp.integration.outbox.mongo;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import io.vanillabp.integration.adapter.spi.PhaseTwoDispatch;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import io.vanillabp.integration.outbox.PhaseTwoOutboxProperties;
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
@EnableConfigurationProperties(PhaseTwoOutboxProperties.class)
public class MongoPhaseTwoOutboxAutoConfiguration {

  /**
   * The task scheduler used to poll the outbox. Only registered if the application
   * does not define its own {@link TaskScheduler} bean;
   * <code>&#64;EnableScheduling</code> is not required.
   *
   * @return The task scheduler
   */
  @Bean
  @ConditionalOnMissingBean(TaskScheduler.class)
  public ThreadPoolTaskScheduler vanillaBpOutboxTaskScheduler() {

    final var taskScheduler = new ThreadPoolTaskScheduler();
    taskScheduler.setPoolSize(1);
    taskScheduler.setThreadNamePrefix("vanillabp-outbox-");
    taskScheduler.setDaemon(true);
    return taskScheduler;

  }

  /**
   * @param mongoTemplate The template used to claim and remove entries
   * @param phaseTwoDispatch The bean dispatched to
   * @param taskScheduler The task scheduler running the poller
   * @param properties The <code>vanillabp.outbox</code> properties
   * @return The dispatcher polling the outbox collection
   */
  @Bean
  public MongoPhaseTwoOutboxDispatcher vanillaBpMongoPhaseTwoOutboxDispatcher(
      final MongoTemplate mongoTemplate,
      final ObjectProvider<PhaseTwoDispatch> phaseTwoDispatch,
      final TaskScheduler taskScheduler,
      final PhaseTwoOutboxProperties properties) {

    return new MongoPhaseTwoOutboxDispatcher(
        mongoTemplate, phaseTwoDispatch, taskScheduler, properties);

  }

  /**
   * @param mongoTemplate The template used to write entries within the current transaction
   * @param dispatcher The dispatcher triggered right after a commit
   * @return The {@link PhaseTwoOutbox} used by the process services
   */
  @Bean
  public MongoPhaseTwoOutbox vanillaBpMongoPhaseTwoOutbox(
      final MongoTemplate mongoTemplate,
      final MongoPhaseTwoOutboxDispatcher dispatcher) {

    return new MongoPhaseTwoOutbox(mongoTemplate, dispatcher);

  }

}
