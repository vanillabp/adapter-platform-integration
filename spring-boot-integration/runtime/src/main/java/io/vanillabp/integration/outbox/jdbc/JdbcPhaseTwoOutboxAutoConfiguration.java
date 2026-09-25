package io.vanillabp.integration.outbox.jdbc;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.PlatformTransactionManager;

import io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.config.GruelboxOutboxProperties;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.outbox.gruelbox.GruelboxPhaseTwoOutboxAutoConfiguration;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.utils.config.JpaSpringDataUtilConfiguration;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-configuration of the default {@link PhaseTwoOutbox} for JPA-based aggregate
 * persistence: the outbox VanillaBP writes itself, which is the same store and the same
 * dispatcher a Quarkus application runs
 * ({@link JdbcPhaseTwoOutboxStore}). Active whenever Spring Data JPA is on the classpath
 * and exactly one {@link EntityManagerFactory} exists - it COEXISTS with the MongoDB
 * default: each workflow aggregate is served by the outbox matching its persistence
 * (selection per aggregate, see
 * {@link io.vanillabp.integration.spi.PhaseTwoOutboxAware}), so outbox entries always
 * ride the aggregate's own transaction even in mixed-persistence applications. Disable
 * via <code>vanillabp.outbox.jdbc.enabled</code> if the default (including its table and
 * background dispatcher) is unwanted.
 * <p>
 * The tables (<code>VANILLABP_PHASE_TWO_OUTBOX</code> and
 * <code>VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD</code>, overridable via
 * <code>vanillabp.outbox.jdbc.table</code> and
 * <code>vanillabp.outbox.jdbc.payload-table</code>; a renamed outbox renames the payload
 * table with it) are created while the outbox bean is built,
 * unless <code>vanillabp.outbox.create-schema</code> is set to <code>false</code> - their
 * existence is then verified instead, and the artifact
 * <code>io.vanillabp:vanillabp-schema</code> carries the statements for the migration
 * tool of the application.
 * <p>
 * An application which ran the gruelbox store before this one gets a message about what
 * that store still holds, see
 * {@link #reportWhatTheFormerStoreStillHolds(DataSource, String)}. Gruelbox itself is
 * still available: setting <code>vanillabp.outbox.gruelbox.enabled</code> to
 * <code>true</code> switches this default off and
 * {@link io.vanillabp.integration.outbox.gruelbox.GruelboxPhaseTwoOutboxAutoConfiguration}
 * on.
 */
@AutoConfiguration(
    after = JpaSpringDataUtilConfiguration.class,
    afterName = {
        "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration", "org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration", "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration"
    })
@ConditionalOnClass(JpaRepository.class)
@ConditionalOnSingleCandidate(EntityManagerFactory.class)
@ConditionalOnBean({
    DataSource.class, PlatformTransactionManager.class
})
@ConditionalOnBooleanProperty(name = "vanillabp.outbox.jdbc.enabled", matchIfMissing = true)
@ConditionalOnProperty(name = GruelboxOutboxProperties.ENABLED, havingValue = "false", matchIfMissing = true)
@EnableConfigurationProperties(VanillaBpConfigurationProperties.class)
@Slf4j
public class JdbcPhaseTwoOutboxAutoConfiguration {

  /**
   * The name of the default JPA outbox bean - used by the resolver to attribute
   * JPA-persisted aggregates to THE default when several outbox beans exist.
   */
  public static final String DEFAULT_OUTBOX_BEAN_NAME = "vanillaBpJdbcPhaseTwoOutbox";

  /**
   * The name of the store holding the payloads of the calls which carry one. A bean of
   * its own so the outbox and its dispatcher use the same one, and so an application may
   * replace it.
   */
  public static final String DEFAULT_PAYLOAD_STORE_BEAN_NAME = "vanillaBpJdbcPhaseTwoPayloadStore";

  /**
   * The table gruelbox stored its entries in, which is where an application upgrading
   * from that store may still have entries waiting. The name comes from the
   * configuration which builds that store, so both halves of the upgrade read it in one
   * place. It is a compile-time constant, so this class does not load the gruelbox
   * configuration at runtime and works without the library on the classpath.
   */
  private static final String FORMER_OUTBOX_TABLE_NAME = GruelboxPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_TABLE_NAME;

  /**
   * Built by Spring Boot while it applies its auto-configurations, and only where the
   * conditions above hold. Nothing in VanillaBP builds it.
   */
  public JdbcPhaseTwoOutboxAutoConfiguration() {
  }

  /**
   * Where the payload of a phase-two call which carries one is stored while its entry
   * waits. It lies in a table of its own and the entry names it (see decision 62 in the
   * repository's DECISIONS.md).
   * <p>
   * The connection is the one Spring binds to the running transaction, so a payload
   * becomes visible exactly when the entry does. The table is created together with the
   * outbox table, by the dispatcher of the outbox bean below.
   *
   * @param dataSource The data source the payload table lives in
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree, naming the
   *          table where the application configured one of its own
   * @return The payload store of the default JDBC outbox
   */
  @Bean(DEFAULT_PAYLOAD_STORE_BEAN_NAME)
  @ConditionalOnMissingBean(name = DEFAULT_PAYLOAD_STORE_BEAN_NAME)
  public JdbcPhaseTwoPayloadStore vanillaBpJdbcPhaseTwoPayloadStore(
      final DataSource dataSource,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    return new JdbcPhaseTwoPayloadStore(
        JdbcPhaseTwoOutbox.connectionsOf(dataSource), JdbcPhaseTwoOutboxStore
            .payloadTableName(vanillaBpProperties.getOutbox()));

  }

  /**
   * The phase-two outbox of every workflow aggregate this application persists in its
   * relational database. Spring builds it where Spring Data JPA is on the classpath, there
   * is exactly one {@link EntityManagerFactory} next to a {@link DataSource} and a
   * transaction manager, <code>vanillabp.outbox.jdbc.enabled</code> is not
   * <code>false</code> and <code>vanillabp.outbox.gruelbox.enabled</code> is not
   * <code>true</code>; an aggregate living in MongoDB is served by the MongoDB outbox
   * beside it.
   *
   * @param dataSource The data source holding the outbox table
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree carrying the
   *          <code>vanillabp.outbox</code> section (registered here as well so the
   *          outbox works in contexts without the full VanillaBP auto-configuration)
   * @param payloadStore Where the payload of a call which carries one is written
   * @param phaseTwoRouter Provider of the router a dispatched entry is handed to
   * @param metrics Provider of what a blocked entry is counted into
   * @return The {@link PhaseTwoOutbox} used by the process services, dispatching on its
   *         own poller and its own threads - no
   *         {@link org.springframework.scheduling.TaskScheduler} is registered or used
   */
  @Bean(DEFAULT_OUTBOX_BEAN_NAME)
  public JdbcPhaseTwoOutbox vanillaBpJdbcPhaseTwoOutbox(
      final DataSource dataSource,
      final VanillaBpConfigurationProperties vanillaBpProperties,
      @Qualifier(DEFAULT_PAYLOAD_STORE_BEAN_NAME) final JdbcPhaseTwoPayloadStore payloadStore,
      final ObjectProvider<PhaseTwoRouter> phaseTwoRouter,
      final ObjectProvider<VanillaBpMetrics> metrics) {

    final var outbox = new JdbcPhaseTwoOutbox(
        dataSource, vanillaBpProperties.getOutbox(), payloadStore, phaseTwoRouter, metrics);
    reportWhatTheFormerStoreStillHolds(dataSource, outbox.getTableName());
    return outbox;

  }

  /**
   * Says what the gruelbox store still holds, for an application which upgrades onto this
   * one. Its entries are in a table of its own and nothing reads that table any more, so
   * a workflow which was waiting for one of them waits forever - and nothing else would
   * say so.
   * <p>
   * Two ways out, and the message names both: drain the entries with the version which
   * still dispatched them, or keep running gruelbox by setting
   * {@link GruelboxOutboxProperties#ENABLED} to <code>true</code>. A warning and
   * not a failure, because the entries may as well be the remains of an application which
   * left that store behind long ago.
   *
   * @param dataSource The data source the tables live in
   * @param tableName The table this store writes into, named in the message so the two
   *          are not mixed up
   */
  private static void reportWhatTheFormerStoreStillHolds(
      final DataSource dataSource,
      final String tableName) {

    final var waiting = entriesWaitingInFormerStore(dataSource);
    if (waiting <= 0) {
      return;
    }
    log
        .warn(
            """
                The table '{}' of the former gruelbox store holds {} entry/entries which were never \
                dispatched, and this application now writes its phase-two entries into '{}'. Nothing \
                reads '{}' any more, so every workflow waiting for one of those entries waits \
                forever. Either
                - start the previous version of this application once and let it dispatch what is \
                left, or
                - set '{}' to 'true' to keep using gruelbox.
                Once '{}' holds nothing undispatched, this message is gone and the table can be \
                dropped.""",
            FORMER_OUTBOX_TABLE_NAME,
            waiting,
            tableName,
            FORMER_OUTBOX_TABLE_NAME,
            // the key comes from the constant the condition of this class reads, so a
            // rename cannot leave this line naming a key which is gone
            GruelboxOutboxProperties.ENABLED,
            FORMER_OUTBOX_TABLE_NAME);

  }

  /**
   * How many entries the gruelbox table holds which were never dispatched.
   *
   * @param dataSource The data source the table lives in
   * @return The number of entries, zero where the table does not exist or cannot be read
   */
  private static long entriesWaitingInFormerStore(
      final DataSource dataSource) {

    try (var connection = dataSource.getConnection()) {
      if (!JdbcSchema.tableExistsQuietly(connection, FORMER_OUTBOX_TABLE_NAME)) {
        return 0;
      }
      try (var statement = connection
          .prepareStatement("SELECT COUNT(*) FROM %s WHERE processed = ?".formatted(FORMER_OUTBOX_TABLE_NAME))) {
        statement.setBoolean(1, false);
        try (var resultSet = statement.executeQuery()) {
          return resultSet.next() ? resultSet.getLong(1) : 0;
        }
      }
    } catch (final SQLException e) {
      // a table of that name which is not gruelbox' one - the application knows its
      // database, and a boot must not end over a question nobody asked
      log.debug("Could not read the table '{}' of the former gruelbox store", FORMER_OUTBOX_TABLE_NAME, e);
      return 0;
    }

  }

}
