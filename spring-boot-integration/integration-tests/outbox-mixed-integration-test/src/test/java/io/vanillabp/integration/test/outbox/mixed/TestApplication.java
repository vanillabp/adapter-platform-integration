package io.vanillabp.integration.test.outbox.mixed;


import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.outbox.jdbc.JdbcPhaseTwoOutbox;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.PhaseTwoOutboxAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.TransactionRunnerAware;
import io.vanillabp.integration.utils.impl.MongoDbSpringDataUtil;
import io.vanillabp.integration.utils.impl.SpringDataUtilBasedAggregatePersistenceSupport;
import io.vanillabp.integration.workflowtask.SpringTransactionRunner;
import jakarta.persistence.EntityManagerFactory;

/**
 * Test application which persists aggregates with JPA (H2) and with MongoDB in ONE
 * application. This is the mixed-persistence scenario, for example during a migration:
 * <ul>
 * <li>both transaction managers are defined explicitly. Spring Boot does not create its
 * JPA manager as soon as any other {@code TransactionManager} bean exists. The JPA
 * manager keeps the usual name {@code transactionManager}, which the default JDBC
 * outbox joins;</li>
 * <li>the MongoDB aggregate gets an explicit persistence based on Spring Data. The
 * fallback of the platform uses THE single {@code SpringDataUtil} bean, and that is the
 * JPA one here;</li>
 * <li>the "hot" JPA aggregate gets its OWN outbox on its own tables ({@code HOT_OUTBOX}
 * and {@code HOT_PAYLOAD}) through a {@link PhaseTwoOutboxAware} bean. This is how a user
 * separates a process under high load: its own outbox plus the bean which assigns
 * it.</li>
 * </ul>
 */
@SpringBootApplication
public class TestApplication {

  public static final String HOT_OUTBOX_TABLE = "HOT_OUTBOX";

  public static final String HOT_PAYLOAD_TABLE = "HOT_PAYLOAD";

  @Bean("transactionManager")
  @Primary
  public JpaTransactionManager transactionManager(
      final EntityManagerFactory entityManagerFactory) {

    return new JpaTransactionManager(entityManagerFactory);

  }

  @Bean("mongoTransactionManager")
  public MongoTransactionManager mongoTransactionManager(
      final MongoDatabaseFactory databaseFactory) {

    return new MongoTransactionManager(databaseFactory);

  }

  /**
   * With a JPA and a MongoDB transaction manager in one application, no manager is THE
   * one. So the application says which aggregate belongs to which manager. Each bean
   * returns a runner bound to the matching manager. VanillaBP runs everything it does with
   * that aggregate inside this runner.
   *
   * @param transactionManager The JPA manager
   * @return The attribution of the JPA aggregate
   */
  @Bean
  public TransactionRunnerAware<JpaAggregate> jpaAggregateTransactions(
      @Qualifier("transactionManager") final PlatformTransactionManager transactionManager) {

    final var runner = new SpringTransactionRunner(transactionManager);
    return new TransactionRunnerAware<>() {

      @Override
      public Class<JpaAggregate> getAggregateClass() {
        return JpaAggregate.class;
      }

      @Override
      public TransactionRunner getTransactionRunner() {
        return runner;
      }

    };

  }

  /**
   * The "hot" aggregate is persisted with JPA as well. So it gets the JPA manager for the
   * same reason.
   *
   * @param transactionManager The JPA manager
   * @return The attribution of the hot aggregate
   */
  @Bean
  public TransactionRunnerAware<HotAggregate> hotAggregateTransactions(
      @Qualifier("transactionManager") final PlatformTransactionManager transactionManager) {

    final var runner = new SpringTransactionRunner(transactionManager);
    return new TransactionRunnerAware<>() {

      @Override
      public Class<HotAggregate> getAggregateClass() {
        return HotAggregate.class;
      }

      @Override
      public TransactionRunner getTransactionRunner() {
        return runner;
      }

    };

  }

  /**
   * The MongoDB aggregate gets the MongoDB manager, which really covers its store.
   *
   * @param transactionManager The MongoDB manager
   * @return The attribution of the MongoDB aggregate
   */
  @Bean
  public TransactionRunnerAware<MongoAggregate> mongoAggregateTransactions(
      @Qualifier("mongoTransactionManager") final PlatformTransactionManager transactionManager) {

    final var runner = new SpringTransactionRunner(transactionManager);
    return new TransactionRunnerAware<>() {

      @Override
      public Class<MongoAggregate> getAggregateClass() {
        return MongoAggregate.class;
      }

      @Override
      public TransactionRunner getTransactionRunner() {
        return runner;
      }

    };

  }

  @Bean
  public TransactionTemplate jpaTransactionTemplate(
      @Qualifier("transactionManager") final PlatformTransactionManager transactionManager) {

    return new TransactionTemplate(transactionManager);

  }

  @Bean
  public TransactionTemplate mongoTransactionTemplate(
      @Qualifier("mongoTransactionManager") final PlatformTransactionManager transactionManager) {

    return new TransactionTemplate(transactionManager);

  }

  /**
   * A persistence based on Spring Data for the MongoDB aggregate. The fallback of the
   * platform uses the single {@code SpringDataUtil} bean, which is the JPA one in this
   * application. So the MongoDB aggregate needs an explicit persistence. That is the usual
   * pattern for applications with mixed persistence.
   *
   * @param applicationContext Used to look up the aggregate's repository
   * @param databaseFactory The MongoDB database factory
   * @param mongoConverter The MongoDB converter
   * @return The MongoDB aggregate's persistence
   */
  @Bean
  public AggregatePersistenceAware<MongoAggregate> mongoAggregatePersistence(
      final ApplicationContext applicationContext,
      final MongoDatabaseFactory databaseFactory,
      final MongoConverter mongoConverter) {

    return new SpringDataUtilBasedAggregatePersistenceSupport<>(
        new MongoDbSpringDataUtil(applicationContext, databaseFactory, mongoConverter), MongoAggregate.class);

  }

  /**
   * The own outbox of the "hot" process. It writes its entries into
   * {@link #HOT_OUTBOX_TABLE} and the payloads into {@link #HOT_PAYLOAD_TABLE}. It is the
   * same kind of store as the platform default, but on tables nobody else writes into. It
   * creates them itself, as the default does.
   *
   * @param dataSource The data source holding the tables
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree, whose outbox
   *          settings this instance uses as well
   * @param phaseTwoRouter Provider of the router dispatched to
   * @param metrics Provider of what a blocked entry is counted into
   * @return The dedicated outbox
   */
  @Bean("hotPhaseTwoOutbox")
  public JdbcPhaseTwoOutbox hotPhaseTwoOutbox(
      final DataSource dataSource,
      final VanillaBpConfigurationProperties vanillaBpProperties,
      final ObjectProvider<PhaseTwoRouter> phaseTwoRouter,
      final ObjectProvider<VanillaBpMetrics> metrics) {

    return new JdbcPhaseTwoOutbox(
        dataSource, vanillaBpProperties.getOutbox(), HOT_OUTBOX_TABLE, new JdbcPhaseTwoPayloadStore(
            JdbcPhaseTwoOutbox.connectionsOf(dataSource), HOT_PAYLOAD_TABLE, JdbcPhaseTwoOutboxStore
                .entriesNamingTheirPayload(HOT_OUTBOX_TABLE)), phaseTwoRouter, metrics);

  }

  /**
   * Assigns the "hot" aggregate to its own outbox. All other aggregates keep the choice
   * of the platform (JPA aggregate → the JDBC default, MongoDB aggregate → the MongoDB
   * default).
   *
   * @param hotPhaseTwoOutbox The dedicated outbox
   * @return The attribution bean
   */
  @Bean
  public PhaseTwoOutboxAware<HotAggregate> hotPhaseTwoOutboxAware(
      @Qualifier("hotPhaseTwoOutbox") final JdbcPhaseTwoOutbox hotPhaseTwoOutbox) {

    return new PhaseTwoOutboxAware<>() {

      @Override
      public Class<HotAggregate> getAggregateClass() {

        return HotAggregate.class;

      }

      @Override
      public PhaseTwoOutbox getPhaseTwoOutbox() {

        return hotPhaseTwoOutbox;

      }

    };

  }

  @Bean
  public RecordingPhaseTwoListener recordingPhaseTwoListener() {

    return new RecordingPhaseTwoListener();

  }

}
