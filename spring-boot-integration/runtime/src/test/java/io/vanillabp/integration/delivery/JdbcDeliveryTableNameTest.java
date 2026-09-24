package io.vanillabp.integration.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema;
import io.vanillabp.integration.config.VanillaBpConfigurationProperties;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * An application which has naming rules for its tables renames the delivery log the way it
 * renames the outbox: <code>vanillabp.outbox.jdbc.delivery-table</code>.
 * <p>
 * The name is followed rather than read back: the table has to be created under the
 * configured name, the record has to land in it, and the default name must not exist at
 * all. A wiring which read the property and then wrote to the constant would pass a test
 * asserting the property and fail this one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class JdbcDeliveryTableNameTest {

  private static final String RENAMED = "OUR_DELIVERIES";

  @Test
  @DisplayName("The record lands in the configured table, and the default one is never created")
  public void theRecordLandsInTheConfiguredTable() throws Exception {

    final var dataSource = h2();
    final var deliveryLog = startedLogOf(dataSource, RENAMED);
    try {
      new TransactionTemplate(new DataSourceTransactionManager(dataSource))
          .executeWithoutResult(status -> assertTrue(deliveryLog.record(aDelivery())));

      try (var connection = dataSource.getConnection()) {
        assertTrue(
            JdbcSchema.tableExists(connection, RENAMED),
            "the startup creates the table the application named");
        assertFalse(
            JdbcSchema.tableExists(connection, JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
            "and nothing creates the default one beside it");
        try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM %s".formatted(RENAMED))) {
          try (var rows = statement.executeQuery()) {
            assertTrue(rows.next());
            assertEquals(1, rows.getInt(1), "the record lies where the application asked for it");
          }
        }
      }

      assertTrue(
          deliveryLog
              .recordedDelivery("job-1")
              .isPresent(),
          "and the log reads it back out of the same table");
    } finally {
      deliveryLog.stop();
      dataSource.destroy();
    }

  }

  @Test
  @DisplayName("An application which names no table gets the default one")
  public void theDefaultIsUsedWhereNobodyNamesATable() throws Exception {

    final var dataSource = h2();
    final var deliveryLog = startedLogOf(dataSource, null);
    try {
      try (var connection = dataSource.getConnection()) {
        assertTrue(
            JdbcSchema.tableExists(connection, JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
            "the name every application had before this key existed");
      }
    } finally {
      deliveryLog.stop();
      dataSource.destroy();
    }

  }

  /**
   * Builds and starts what a Spring Boot application builds at startup: the delivery log
   * and the hook which creates its table.
   *
   * @param dataSource The database the records go into
   * @param deliveryTable What the application wrote into
   *          <code>vanillabp.outbox.jdbc.delivery-table</code>, or <code>null</code>
   * @return The started log
   */
  private static JdbcTaskDeliveryLog startedLogOf(
      final DataSource dataSource,
      final String deliveryTable) {

    final var vanillaBpProperties = new VanillaBpConfigurationProperties();
    vanillaBpProperties
        .getOutbox()
        .getJdbc()
        .setDeliveryTable(deliveryTable);
    final var autoConfiguration = new JdbcTaskDeliveryLogAutoConfiguration();
    final var deliveryLog = autoConfiguration.vanillaBpJdbcTaskDeliveryLog(dataSource, vanillaBpProperties);
    autoConfiguration
        .vanillaBpJdbcTaskDeliveryLogStartup(deliveryLog, vanillaBpProperties)
        .afterSingletonsInstantiated();
    return deliveryLog;

  }

  /**
   * @return A database of this test's own, so the tables of one test do not answer for
   *         another
   */
  private static SingleConnectionDataSource h2() {

    final var dataSource = new SingleConnectionDataSource(
        "jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID()), "sa", "", true);
    dataSource.setDriverClassName("org.h2.Driver");
    return dataSource;

  }

  /**
   * @return One processed delivery, with the values a record needs
   */
  private static TaskDelivery aDelivery() {

    return new TaskDelivery(
        "job-1", "demo1", "test-module", "TestProcess", "4711", "workflow-4711", "processTask", "Activity_processTask", null, "COMPLETED", null, null, Instant
            .now(), null);

  }

}
