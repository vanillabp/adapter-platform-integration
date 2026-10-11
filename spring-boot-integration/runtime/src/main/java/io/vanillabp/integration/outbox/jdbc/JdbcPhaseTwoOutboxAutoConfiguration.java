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
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.utils.config.JpaSpringDataUtilConfiguration;
import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;

/**
 * Auto-configuration of the default {@link PhaseTwoOutbox} for aggregates persisted with
 * JPA. VanillaBP writes this outbox itself. It is the same store and the same dispatcher a
 * Quarkus application runs ({@link JdbcPhaseTwoOutboxStore}). It is active whenever Spring
 * Data JPA is on the classpath and exactly one {@link EntityManagerFactory} exists. It
 * lives SIDE BY SIDE with the MongoDB default. Each workflow aggregate is served by the
 * outbox which matches its persistence. The choice is made per aggregate, see
 * {@link io.vanillabp.integration.spi.PhaseTwoOutboxAware}. So outbox entries always ride
 * the aggregate's own transaction, also in applications with mixed persistence. Switch
 * it off with <code>vanillabp.outbox.jdbc.enabled</code> if you do not want the default,
 * with its table and its background dispatcher.
 * <p>
 * The tables are <code>VANILLABP_PHASE_TWO_OUTBOX</code> and
 * <code>VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD</code>. You can rename them with
 * <code>vanillabp.outbox.jdbc.table</code> and
 * <code>vanillabp.outbox.jdbc.payload-table</code>. A renamed outbox renames the payload
 * table with it. The tables are created while the outbox bean is built, unless
 * <code>vanillabp.outbox.create-schema</code> is set to <code>false</code>. Then the
 * startup only checks that they exist. The artifact
 * <code>io.vanillabp:vanillabp-schema</code> carries the statements for the migration
 * tool of the application.
 * <p>
 * An application which ran the gruelbox store before this one gets a message about what
 * that store still holds, see
 * {@link #reportWhatTheFormerStoreStillHolds(DataSource, String, VanillaBpConfigurationProperties)}.
 * That store is no longer part of VanillaBP. It lives in
 * <code>io.vanillabp:gruelbox-phase-two-outbox</code>. An application which adds that
 * artifact gets this configuration out of the way. The artifact registers the outbox bean
 * under the name below, and the condition on this class reads that name.
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
// a store from another artifact registers the outbox bean under this name and is applied
// before this configuration. That is how it takes the place of this default
@ConditionalOnMissingBean(name = JdbcPhaseTwoOutboxAutoConfiguration.DEFAULT_OUTBOX_BEAN_NAME)
@EnableConfigurationProperties(VanillaBpConfigurationProperties.class)
@Slf4j
public class JdbcPhaseTwoOutboxAutoConfiguration {

  /**
   * The name of the default JPA outbox bean. When several outbox beans exist, the resolver
   * uses it to assign aggregates persisted with JPA to THE default.
   */
  public static final String DEFAULT_OUTBOX_BEAN_NAME = "vanillaBpJdbcPhaseTwoOutbox";

  /**
   * The name of the store which holds the payloads of the calls which carry one. It is a
   * bean of its own, so the outbox and its dispatcher use the same one. And an application
   * may replace it.
   */
  public static final String DEFAULT_PAYLOAD_STORE_BEAN_NAME = "vanillaBpJdbcPhaseTwoPayloadStore";

  /**
   * The table the gruelbox store writes. An application which upgrades to this store may
   * still have entries waiting there. The name belongs to the library. It is gruelbox's
   * default, and the only table its schema migration ever creates. So it is written out
   * here, and not read from a store which this repository does not carry.
   */
  private static final String FORMER_OUTBOX_TABLE_NAME = "TXNO_OUTBOX";

  /**
   * The artifact an application adds to keep running the gruelbox store. The message
   * above names it, so the remedy is one line to copy.
   */
  private static final String THE_ARTIFACT_CARRYING_THE_FORMER_STORE = "io.vanillabp:gruelbox-phase-two-outbox";

  /**
   * Built by Spring Boot while it applies its auto-configurations, and only where the
   * conditions above hold. Nothing in VanillaBP builds it.
   */
  public JdbcPhaseTwoOutboxAutoConfiguration() {
  }

  /**
   * Where the payload of a phase-two call is stored while its entry waits. It lies in a
   * table of its own, and the entry names it (see decision 62 in the repository's
   * DECISIONS.md).
   * <p>
   * The connection is the one Spring binds to the running transaction. So a payload
   * becomes visible exactly when the entry does. The dispatcher of the outbox bean below
   * creates the table together with the outbox table.
   *
   * @param dataSource The data source the payload table lives in
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree. It names the table
   *          where the application configured one of its own
   * @return The payload store of the default JDBC outbox
   */
  @Bean(DEFAULT_PAYLOAD_STORE_BEAN_NAME)
  @ConditionalOnMissingBean(name = DEFAULT_PAYLOAD_STORE_BEAN_NAME)
  public JdbcPhaseTwoPayloadStore vanillaBpJdbcPhaseTwoPayloadStore(
      final DataSource dataSource,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    return new JdbcPhaseTwoPayloadStore(
        JdbcPhaseTwoOutbox.connectionsOf(dataSource), JdbcPhaseTwoOutboxStore
            .payloadTableName(vanillaBpProperties.getOutbox()), JdbcPhaseTwoOutboxStore
                .entriesNamingTheirPayload(vanillaBpProperties.getOutbox()));

  }

  /**
   * The phase-two outbox of every workflow aggregate this application persists in its
   * relational database. Spring builds it where Spring Data JPA is on the classpath, there
   * is exactly one {@link EntityManagerFactory} next to a {@link DataSource} and a
   * transaction manager, <code>vanillabp.outbox.jdbc.enabled</code> is not
   * <code>false</code>, and no other artifact took the name of this bean. The MongoDB
   * outbox beside it serves an aggregate which lives in MongoDB.
   *
   * @param dataSource The data source holding the outbox table
   * @param vanillaBpProperties The bound <code>vanillabp.*</code> tree with the
   *          <code>vanillabp.outbox</code> section. It is registered here as well, so the
   *          outbox also works in contexts without the full VanillaBP auto-configuration
   * @param payloadStore Where the payload of a call which carries one is written
   * @param phaseTwoRouter Provider of the router a dispatched entry is handed to
   * @param metrics Provider of what a blocked entry is counted into
   * @return The {@link PhaseTwoOutbox} which the process services use. It dispatches on
   *         its own poller and its own threads. No
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
    reportWhatTheFormerStoreStillHolds(dataSource, outbox.getTableName(), vanillaBpProperties);
    return outbox;

  }

  /**
   * Says what the gruelbox store still holds, for an application which upgrades to this
   * one. Its entries are in a table of its own, and nothing reads that table any more. So
   * a workflow which was waiting for one of them waits forever, and nothing else would say
   * so.
   * <p>
   * There are two ways out, and the message names both. Either drain the entries with the
   * version which still dispatched them. Or keep running gruelbox by adding the artifact
   * which carries that store. It is a warning and not a failure, because the entries may
   * just be the remains of an application which left that store behind long ago.
   *
   * @param dataSource The data source the tables live in
   * @param tableName The table this store writes into. The message names it, so the two
   *          are not mixed up
   * @param vanillaBpProperties The bound tree. It carries the collection the whole start
   *          reports into
   */
  private static void reportWhatTheFormerStoreStillHolds(
      final DataSource dataSource,
      final String tableName,
      final VanillaBpConfigurationProperties vanillaBpProperties) {

    final var waiting = entriesWaitingInFormerStore(dataSource);
    if (waiting <= 0) {
      return;
    }
    vanillaBpProperties
        .startupFindings()
        .warn(
            io.vanillabp.integration.spi.startup.StartupTopic.STORED_STATE,
            "table '%s'".formatted(FORMER_OUTBOX_TABLE_NAME),
            """
                The table '%s' of the former gruelbox store holds %d entry/entries which were never \
                dispatched, and this application now writes its phase-two entries into '%s'. Nothing \
                reads '%s' any more, so every workflow waiting for one of those entries waits \
                forever. Either
                - start the previous version of this application once and let it dispatch what is \
                left, or
                - add '%s' to keep running gruelbox.
                Once '%s' holds nothing undispatched, this message is gone and the table can be \
                dropped."""
                .formatted(
                    FORMER_OUTBOX_TABLE_NAME,
                    waiting,
                    tableName,
                    FORMER_OUTBOX_TABLE_NAME,
                    THE_ARTIFACT_CARRYING_THE_FORMER_STORE,
                    FORMER_OUTBOX_TABLE_NAME));

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
      // a table of that name which is not gruelbox's. The application knows its database,
      // and a boot must not end over a question nobody asked
      log.debug("Could not read the table '{}' of the former gruelbox store", FORMER_OUTBOX_TABLE_NAME, e);
      return 0;
    }

  }

}
