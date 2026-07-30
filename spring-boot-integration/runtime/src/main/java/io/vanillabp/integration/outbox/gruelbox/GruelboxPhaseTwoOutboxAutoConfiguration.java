package io.vanillabp.integration.outbox.gruelbox;

import java.sql.SQLException;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.PlatformTransactionManager;

import com.gruelbox.transactionoutbox.DefaultPersistor;
import com.gruelbox.transactionoutbox.Dialect;
import com.gruelbox.transactionoutbox.TransactionOutbox;
import com.gruelbox.transactionoutbox.spring.SpringInstantiator;
import com.gruelbox.transactionoutbox.spring.SpringTransactionManager;

import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.spi.PhaseTwoCall;
import io.vanillabp.integration.adapter.spi.PhaseTwoOperation;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.utils.config.JpaSpringDataUtilConfiguration;
import jakarta.persistence.EntityManagerFactory;

/**
 * Auto-configuration of the default {@link PhaseTwoOutbox} for JPA-based aggregate
 * persistence, backed by the
 * <a href="https://github.com/gruelbox/transaction-outbox">gruelbox
 * transaction-outbox</a>. Only active under the same conditions as the JPA
 * {@link io.vanillabp.integration.utils.SpringDataUtil} (Spring Data JPA on the
 * classpath, exactly one {@link EntityManagerFactory}) and if the application did not
 * define its own {@link PhaseTwoOutbox} bean. It is ordered before the MongoDB
 * implementation: if both JPA and MongoDB are configured, JPA wins deterministically -
 * consistent with the {@link io.vanillabp.integration.utils.SpringDataUtil}
 * auto-configurations.
 * <p>
 * The outbox table (<code>TXNO_OUTBOX</code>) is created automatically via gruelbox's
 * schema migration unless <code>vanillabp.outbox.create-schema</code> is set to
 * <code>false</code> (see the module's <code>README.md</code> for managing the schema
 * manually).
 * <p>
 * <strong>Contract mapping (deviations):</strong> the {@link PhaseTwoOutbox} contract
 * is mapped onto gruelbox's native capabilities: idempotency keys become
 * <code>uniqueRequestId</code>s (unique constraint of <code>TXNO_OUTBOX</code>), "DONE
 * instead of delete" becomes gruelbox's retention of processed entries with a unique
 * request ID (<code>vanillabp.outbox.retention</code> maps to gruelbox's retention
 * threshold; expired entries are deleted by the background flush), and blocking after
 * <code>vanillabp.outbox.block-after-attempts</code> failed attempts is gruelbox's
 * native blocklisting. The {@link PhaseTwoCall#args()} map is NOT transported (empty
 * for all operations existing today - see {@link GruelboxPhaseTwoDispatch}).
 */
@AutoConfiguration(
    after = JpaSpringDataUtilConfiguration.class,
    afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration", "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration", "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
    })
@ConditionalOnClass({
    JpaRepository.class, TransactionOutbox.class
})
@ConditionalOnSingleCandidate(EntityManagerFactory.class)
@ConditionalOnBean({
    DataSource.class, PlatformTransactionManager.class
})
@ConditionalOnMissingBean(PhaseTwoOutbox.class)
@EnableConfigurationProperties(VanillaBpConfigurationProperties.class)
public class GruelboxPhaseTwoOutboxAutoConfiguration {

  /**
   * The gruelbox {@link TransactionOutbox} enlisting entries in Spring-managed JDBC
   * transactions and instantiating the scheduled {@link GruelboxPhaseTwoDispatch}
   * from the application context.
   *
   * @param applicationContext Used to resolve the scheduled bean at dispatch time
   * @param transactionManager The Spring transaction manager entries are enlisted with
   * @param dataSource The data source storing the outbox table
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree carrying the
   *          <code>vanillabp.outbox</code> section (registered here as well so the
   *          outbox works in contexts without the full VanillaBP auto-configuration)
   * @return The transaction outbox
   */
  @Bean
  @ConditionalOnMissingBean(TransactionOutbox.class)
  public TransactionOutbox vanillaBpTransactionOutbox(
      final ApplicationContext applicationContext,
      final PlatformTransactionManager transactionManager,
      final DataSource dataSource,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    final var properties = vanillaBpProperties.getOutbox();
    return TransactionOutbox
        .builder()
        .transactionManager(new SpringTransactionManager(transactionManager, dataSource))
        .instantiator(new SpringInstantiator(applicationContext))
        .persistor(DefaultPersistor
            .builder()
            .dialect(detectDialect(dataSource))
            .migrate(properties.isCreateSchema())
            .build())
        .attemptFrequency(properties.getAttemptFrequency())
        .blockAfterAttempts(properties.getBlockAfterAttempts())
        .retentionThreshold(properties.getRetention())
        .initializeImmediately(true)
        .build();

  }

  /**
   * @param transactionOutbox The gruelbox transaction outbox
   * @return The {@link PhaseTwoOutbox} used by the process services
   */
  @Bean
  public GruelboxPhaseTwoOutbox vanillaBpGruelboxPhaseTwoOutbox(
      final TransactionOutbox transactionOutbox) {

    return new GruelboxPhaseTwoOutbox(transactionOutbox);

  }

  /**
   * The bean invoked by the outbox at dispatch time (resolved by gruelbox's
   * <code>SpringInstantiator</code> as the unique bean of type
   * {@link GruelboxPhaseTwoDispatch}). It rebuilds the {@link PhaseTwoCall} and
   * routes it through the core's {@link PhaseTwoRouter}.
   *
   * @param phaseTwoRouter Provider of the router dispatched to
   * @return The dispatch bean
   */
  @Bean
  public GruelboxPhaseTwoDispatch vanillaBpGruelboxPhaseTwoDispatch(
      final ObjectProvider<PhaseTwoRouter> phaseTwoRouter) {

    return (
        operation,
        workflowModuleId,
        bpmnProcessId,
        workflowAggregateId,
        adapterId) -> phaseTwoRouter
            .getObject()
            .dispatch(new PhaseTwoCall(
                PhaseTwoOperation
                    .valueOf(operation), workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId, Map.of()));

  }

  /**
   * @param transactionOutbox The gruelbox transaction outbox
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree carrying the
   *          <code>vanillabp.outbox</code> section (registered here as well so the
   *          outbox works in contexts without the full VanillaBP auto-configuration)
   * @return The dispatcher polling the outbox for recovery, retries and retention
   *         cleanup (private single-thread executor - no
   *         {@link org.springframework.scheduling.TaskScheduler} involved)
   */
  @Bean
  public GruelboxPhaseTwoOutboxDispatcher vanillaBpGruelboxPhaseTwoOutboxDispatcher(
      final TransactionOutbox transactionOutbox,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    return new GruelboxPhaseTwoOutboxDispatcher(transactionOutbox, vanillaBpProperties.getOutbox());

  }

  /**
   * Detects the gruelbox SQL dialect from the data source's metadata.
   *
   * @param dataSource The data source used for the outbox table
   * @return The dialect
   * @throws IllegalStateException If the database is not supported by gruelbox
   */
  private static Dialect detectDialect(
      final DataSource dataSource) {

    final String productName;
    try (var connection = dataSource.getConnection()) {
      productName = connection.getMetaData().getDatabaseProductName();
    } catch (SQLException e) {
      throw new IllegalStateException(
          "Could not determine the database product name used to configure the VanillaBP phase-two outbox!", e);
    }
    final var product = productName.toLowerCase();
    if (product.contains("h2")) {
      return Dialect.H2;
    }
    if (product.contains("postgres")) {
      return Dialect.POSTGRESQL_9;
    }
    if (product.contains("mysql") || product.contains("mariadb")) {
      return Dialect.MY_SQL_8;
    }
    if (product.contains("oracle")) {
      return Dialect.ORACLE;
    }
    if (product.contains("microsoft")) {
      return Dialect.MS_SQL_SERVER;
    }
    throw new IllegalStateException(
        """
            Database '%s' is not supported by the gruelbox-based VanillaBP phase-two outbox! \
            Define your own com.gruelbox.transactionoutbox.TransactionOutbox bean or provide a custom \
            implementation of io.vanillabp.integration.adapter.spi.PhaseTwoOutbox."""
            .formatted(productName));

  }

}
