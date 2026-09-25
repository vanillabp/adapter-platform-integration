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
 * Test application combining JPA (H2) and MongoDB aggregate persistence in ONE
 * application (the mixed-persistence scenario, e.g. during a migration):
 * <ul>
 * <li>both transaction managers are defined explicitly (Spring Boot's JPA
 * auto-configured one backs off as soon as any other {@code TransactionManager} bean
 * exists); the JPA one keeps the conventional name {@code transactionManager} the
 * JDBC outbox default enlists with;</li>
 * <li>the MongoDB aggregate gets a Spring-Data-based persistence explicitly (the
 * platform's fallback uses THE single {@code SpringDataUtil} bean, which is the JPA
 * one here);</li>
 * <li>the "hot" JPA aggregate gets a DEDICATED outbox on tables of its own
 * ({@code HOT_OUTBOX} and {@code HOT_PAYLOAD}) via a {@link PhaseTwoOutboxAware} bean -
 * the user-side recipe for isolating a high-load process (an outbox of its own plus the
 * attribution bean).</li>
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
   * With a JPA and a MongoDB transaction manager in one application, no manager is
   * THE one - so the application says which aggregate belongs to which. Each bean returns a
   * runner bound to the matching manager, and VanillaBP runs everything it does with that
   * aggregate inside it.
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
   * The "hot" aggregate is JPA-persisted as well, and gets the JPA manager for the same
   * reason.
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
   * The MongoDB aggregate gets the MongoDB manager - the one which really covers its store.
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
   * Spring-Data-based persistence for the MongoDB aggregate: the platform's
   * fallback uses the single {@code SpringDataUtil} bean (the JPA one in this
   * application), so the MongoDB aggregate needs its persistence assigned
   * explicitly - the regular pattern for mixed-persistence applications.
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
   * The dedicated outbox for the "hot" process, writing its entries into
   * {@link #HOT_OUTBOX_TABLE} and the payloads into {@link #HOT_PAYLOAD_TABLE}. It is the
   * same store the platform default is, on tables nothing else writes into, and it creates
   * them itself the way the default does.
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
   * Attributes the "hot" aggregate to its dedicated outbox - all other aggregates
   * keep the platform-default selection (JPA aggregate → the JDBC default, MongoDB
   * aggregate → MongoDB default).
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
