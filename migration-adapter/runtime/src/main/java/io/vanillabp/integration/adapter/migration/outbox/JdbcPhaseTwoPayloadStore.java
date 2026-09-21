package io.vanillabp.integration.adapter.migration.outbox;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoPayloadStore;
import lombok.extern.slf4j.Slf4j;

/**
 * The payload store of every JDBC-backed outbox VanillaBP ships: the gruelbox store on
 * Spring Boot and the own store on Quarkus write into the same table with the same
 * statements, and only the way a connection joins the running transaction differs
 * between the two platforms ({@link JdbcConnectionAccess}).
 * <p>
 * One row per phase-two call which carries a payload, written in the transaction which
 * writes the outbox entry and read once per dispatch attempt of that entry. The row is
 * removed when the entry was dispatched; {@link #removeOlderThan(Instant)} removes what
 * a crash between the two writes left behind.
 * <p>
 * The table is VanillaBP's own, so it is described in
 * <code>io.vanillabp:vanillabp-schema</code> like the other two and created at startup
 * where the application does not manage its schema itself.
 */
@Slf4j
public class JdbcPhaseTwoPayloadStore implements PhaseTwoPayloadStore {

  /**
   * What is appended to the name of the outbox table to get the name of the payload
   * table. It is written the way a table is written here, in capitals with an
   * underscore, while the MongoDB store appends
   * {@link io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties.MongoOutboxProperties#PAYLOAD_COLLECTION_SUFFIX}
   * in the way a collection is written. The two are the same idea in two spellings, so
   * do not pull them together into one string.
   */
  public static final String TABLE_NAME_SUFFIX = "_PAYLOAD";

  /**
   * The name of the table the payloads are stored in where the application configures
   * neither name (override via <code>vanillabp.outbox.jdbc.payload-table</code>). One
   * table per outbox, for the reason the outbox has one table per instance: two
   * applications sharing it would house-keep each other's rows. That is also why the
   * name follows the outbox table: an application which renames the outbox to keep two
   * deployments apart would otherwise share the payloads it wanted to separate.
   */
  public static final String DEFAULT_TABLE_NAME = JdbcPhaseTwoOutboxStore.DEFAULT_TABLE_NAME + TABLE_NAME_SUFFIX;

  private static final String INSERT_PAYLOAD = """
      INSERT INTO %s \
      (REFERENCE, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, PAYLOAD, CREATED_AT) \
      VALUES (?, ?, ?, ?, ?, ?)""";

  private static final String SELECT_PAYLOAD = "SELECT PAYLOAD FROM %s WHERE REFERENCE = ?";

  private static final String DELETE_PAYLOAD = "DELETE FROM %s WHERE REFERENCE = ?";

  private static final String DELETE_EXPIRED_PAYLOADS = "DELETE FROM %s WHERE CREATED_AT < ?";

  /**
   * The index the housekeeping deletes along. Without it that delete reads every
   * payload ever written, which is the cost this table can least afford.
   */
  public static final String CREATE_AGE_INDEX = "CREATE INDEX %s_AGE ON %s (CREATED_AT)";

  private final JdbcConnectionAccess connectionAccess;

  private final String tableName;

  private final String insertPayload;

  private final String selectPayload;

  private final String deletePayload;

  private final String deleteExpiredPayloads;

  /**
   * @param connectionAccess How this platform hands out a connection taking part in the
   *        transaction currently running
   * @param tableName The table to store payloads in
   */
  public JdbcPhaseTwoPayloadStore(
      final JdbcConnectionAccess connectionAccess,
      final String tableName) {

    this.connectionAccess = connectionAccess;
    this.tableName = tableName;
    this.insertPayload = INSERT_PAYLOAD.formatted(tableName);
    this.selectPayload = SELECT_PAYLOAD.formatted(tableName);
    this.deletePayload = DELETE_PAYLOAD.formatted(tableName);
    this.deleteExpiredPayloads = DELETE_EXPIRED_PAYLOADS.formatted(tableName);

  }

  /**
   * @return The table this store reads and writes
   */
  public String getTableName() {

    return tableName;

  }

  @Override
  public void write(
      final PhaseTwoCall call) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(insertPayload)) {
        statement.setString(1, call.payloadReference());
        statement.setString(2, call.workflowModuleId());
        statement.setString(3, call.bpmnProcessId());
        statement.setString(4, call.operation());
        statement.setBytes(5, call.payload());
        statement.setTimestamp(6, Timestamp.from(Instant.now()));
        statement.executeUpdate();
      }
    } catch (final SQLException e) {
      throw new IllegalStateException(
          """
              Could not store the payload of phase two (%s) of BPMN process '%s' of workflow module \
              '%s' in table '%s'!"""
              .formatted(call.operation(), call.bpmnProcessId(), call.workflowModuleId(), tableName), e);
    } finally {
      release(connection);
    }

  }

  @Override
  public byte[] read(
      final String reference) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(selectPayload)) {
        statement.setString(1, reference);
        try (var resultSet = statement.executeQuery()) {
          return resultSet.next() ? resultSet.getBytes(1) : null;
        }
      }
    } catch (final SQLException e) {
      throw new IllegalStateException(
          "Could not read the payload '%s' from table '%s'!".formatted(reference, tableName), e);
    } finally {
      release(connection);
    }

  }

  @Override
  public void remove(
      final String reference) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(deletePayload)) {
        statement.setString(1, reference);
        statement.executeUpdate();
      }
    } catch (final SQLException e) {
      // the entry it belonged to was dispatched, which is what counts - the row is
      // removed by the housekeeping instead, one retention period later
      log.warn("Could not remove the payload '{}' from table '{}'", reference, tableName, e);
    } finally {
      release(connection);
    }

  }

  @Override
  public int removeOlderThan(
      final Instant threshold) {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(deleteExpiredPayloads)) {
        statement.setTimestamp(1, Timestamp.from(threshold));
        return statement.executeUpdate();
      }
    } catch (final SQLException e) {
      log.warn("Could not remove the expired payloads of table '{}'", tableName, e);
      return 0;
    } finally {
      release(connection);
    }

  }

  /**
   * Creates the table and the index the housekeeping deletes along, unless they exist
   * already.
   *
   * @throws IllegalStateException If the DDL fails - naming the way out (manage the
   *         schema manually)
   */
  public void createSchemaIfNotExists() {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      if (JdbcSchema.tableExists(connection, tableName)) {
        return;
      }
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(buildCreateTable(connection, tableName));
        statement.executeUpdate(CREATE_AGE_INDEX.formatted(tableName, tableName));
      }
    } catch (final SQLException e) {
      if (createdConcurrently()) {
        return;
      }
      throw new IllegalStateException(
          """
              Could not create the phase-two payload table '%s'! Set \
              'vanillabp.outbox.create-schema' to 'false' and manage the schema manually if the DDL \
              is not suitable for your database."""
              .formatted(tableName), e);
    } finally {
      release(connection);
    }

  }

  /**
   * Whether the DDL failed because another instance created the table between the check
   * and the statement. Two instances starting together both see no table and both
   * create it, and the loser's boot must not end over it.
   *
   * @return Whether the table is there now
   */
  private boolean createdConcurrently() {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      if (!JdbcSchema.tableExistsQuietly(connection, tableName)) {
        return false;
      }
      log.debug(
          "The phase-two payload table '{}' was created by another instance starting at the same moment",
          tableName);
      return true;
    } catch (final SQLException e) {
      return false;
    } finally {
      release(connection);
    }

  }

  /**
   * Verifies that the table exists, for an application which creates its schema itself.
   * A missing table would otherwise surface at the first call which carries a payload,
   * inside the transaction of the application which planned it.
   *
   * @throws IllegalStateException If the table is missing
   */
  public void validateSchemaExists() {

    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      if (JdbcSchema.tableExists(connection, tableName)) {
        return;
      }
      throw new IllegalStateException(
          """
              The phase-two payload table '%s' does not exist! A phase-two call which carries a \
              payload stores it there, in the transaction which writes the outbox entry, so without \
              the table such a call cannot be planned. Either
              - apply the schema of VanillaBP with your migration tool: the artifact \
              'io.vanillabp:vanillabp-schema' ships the Liquibase changelog \
              'vanillabp/schema/changelog.xml' and the SQL generated from it for Flyway, or
              - let VanillaBP create the table by setting 'vanillabp.outbox.create-schema' to \
              'true' (the default)."""
              .formatted(tableName));
    } catch (final SQLException e) {
      throw new IllegalStateException(
          "Could not check whether the phase-two payload table '%s' exists!".formatted(tableName), e);
    } finally {
      release(connection);
    }

  }

  private void release(
      final Connection connection) {

    if (connection == null) {
      return;
    }
    try {
      connectionAccess.release(connection);
    } catch (final SQLException e) {
      log.warn("Could not release the connection used for the phase-two payload table '{}'", tableName, e);
    }

  }

  /**
   * Builds the CREATE TABLE statement with the types the database at hand spells its
   * timestamps and its binary column in: PostgreSQL knows no <code>BLOB</code>, SQL
   * Server writes a large binary as <code>VARBINARY(MAX)</code> and its
   * <code>TIMESTAMP</code> is a row version, MySQL's timestamp ends in 2038.
   *
   * @param connection The connection used to detect the database
   * @param tableName The table to create
   * @return The CREATE TABLE statement
   */
  private static String buildCreateTable(
      final Connection connection,
      final String tableName) throws SQLException {

    final var product = connection
        .getMetaData()
        .getDatabaseProductName()
        .toLowerCase();
    final String timestampType;
    final String binaryType;
    if (product.contains("microsoft")) {
      timestampType = "DATETIME2";
      binaryType = "VARBINARY(MAX)";
    } else if (product.contains("mysql") || product.contains("mariadb")) {
      timestampType = "DATETIME(6)";
      binaryType = "LONGBLOB";
    } else if (product.contains("postgresql")) {
      timestampType = "TIMESTAMP";
      binaryType = "BYTEA";
    } else {
      timestampType = "TIMESTAMP";
      binaryType = "BLOB";
    }
    return """
        CREATE TABLE %s (\
        REFERENCE VARCHAR(36) PRIMARY KEY, \
        WORKFLOW_MODULE_ID VARCHAR(255) NOT NULL, \
        BPMN_PROCESS_ID VARCHAR(255) NOT NULL, \
        OPERATION VARCHAR(255) NOT NULL, \
        PAYLOAD %s NOT NULL, \
        CREATED_AT %s NOT NULL)"""
        .formatted(tableName, binaryType, timestampType);

  }

}
