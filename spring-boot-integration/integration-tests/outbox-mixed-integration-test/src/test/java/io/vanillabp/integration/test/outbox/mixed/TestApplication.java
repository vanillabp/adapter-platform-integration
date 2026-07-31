package io.vanillabp.integration.test.outbox.mixed;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.convert.MongoConverter;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.gruelbox.transactionoutbox.DefaultPersistor;
import com.gruelbox.transactionoutbox.Dialect;
import com.gruelbox.transactionoutbox.TransactionOutbox;
import com.gruelbox.transactionoutbox.spring.SpringInstantiator;
import com.gruelbox.transactionoutbox.spring.SpringTransactionManager;

import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutboxAware;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.outbox.gruelbox.GruelboxPhaseTwoOutbox;
import io.vanillabp.integration.outbox.gruelbox.GruelboxPhaseTwoOutboxAutoConfiguration;
import io.vanillabp.integration.outbox.gruelbox.GruelboxPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.utils.impl.MongoDbSpringDataUtil;
import io.vanillabp.integration.utils.impl.SpringDataUtilBasedAggregatePersistenceSupport;
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
 * <li>the "hot" JPA aggregate gets a DEDICATED gruelbox outbox on its own table
 * ({@code HOT_OUTBOX}) via a {@link PhaseTwoOutboxAware} bean - the user-side recipe
 * for isolating a high-load process (own {@code TransactionOutbox} + own dispatcher +
 * the attribution bean).</li>
 * </ul>
 */
@SpringBootApplication
public class TestApplication {

  public static final String HOT_OUTBOX_TABLE = "HOT_OUTBOX";

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
   * The dedicated gruelbox instance for the "hot" process, writing to its own table
   * {@link #HOT_OUTBOX_TABLE}. The gruelbox schema migration always targets the
   * default table, so the dedicated table is created here (cloning the structure of
   * the already-migrated default table) and the migration is disabled.
   *
   * @param applicationContext Used to resolve the scheduled bean at dispatch time
   * @param transactionManager The JPA transaction manager (hot aggregates are JPA)
   * @param dataSource The data source storing the outbox table
   * @return The dedicated transaction outbox
   */
  @Bean("hotTransactionOutbox")
  @DependsOn(GruelboxPhaseTwoOutboxAutoConfiguration.DEFAULT_TRANSACTION_OUTBOX_BEAN_NAME)
  public TransactionOutbox hotTransactionOutbox(
      final ApplicationContext applicationContext,
      @Qualifier("transactionManager") final PlatformTransactionManager transactionManager,
      final DataSource dataSource,
      final VanillaBpConfigurationProperties vanillaBpProperties) throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
      statement.executeUpdate(
          "CREATE TABLE IF NOT EXISTS %s AS (SELECT * FROM TXNO_OUTBOX) WITH NO DATA"
              .formatted(HOT_OUTBOX_TABLE));
    }
    final var properties = vanillaBpProperties.getOutbox();
    return TransactionOutbox
        .builder()
        .transactionManager(new SpringTransactionManager(transactionManager, dataSource))
        .instantiator(new SpringInstantiator(applicationContext))
        .persistor(DefaultPersistor
            .builder()
            .dialect(Dialect.H2)
            .migrate(false)
            .tableName(HOT_OUTBOX_TABLE)
            .build())
        .attemptFrequency(properties.getAttemptFrequency())
        .blockAfterAttempts(properties.getBlockAfterAttempts())
        .retentionThreshold(properties.getRetention())
        .initializeImmediately(true)
        .build();

  }

  @Bean("hotPhaseTwoOutbox")
  public GruelboxPhaseTwoOutbox hotPhaseTwoOutbox(
      @Qualifier("hotTransactionOutbox") final TransactionOutbox transactionOutbox) {

    return new GruelboxPhaseTwoOutbox(transactionOutbox);

  }

  @Bean
  public GruelboxPhaseTwoOutboxDispatcher hotPhaseTwoOutboxDispatcher(
      @Qualifier("hotTransactionOutbox") final TransactionOutbox transactionOutbox,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    return new GruelboxPhaseTwoOutboxDispatcher(transactionOutbox, vanillaBpProperties.getOutbox());

  }

  /**
   * Attributes the "hot" aggregate to its dedicated outbox - all other aggregates
   * keep the platform-default selection (JPA aggregate → gruelbox default, MongoDB
   * aggregate → MongoDB default).
   *
   * @param hotPhaseTwoOutbox The dedicated outbox
   * @return The attribution bean
   */
  @Bean
  public PhaseTwoOutboxAware<HotAggregate> hotPhaseTwoOutboxAware(
      @Qualifier("hotPhaseTwoOutbox") final GruelboxPhaseTwoOutbox hotPhaseTwoOutbox) {

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
