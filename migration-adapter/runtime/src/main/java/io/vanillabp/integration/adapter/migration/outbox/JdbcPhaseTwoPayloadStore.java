package io.vanillabp.integration.adapter.migration.outbox;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.jdbc.JdbcDialect;
import io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoPayloadStore;
import lombok.extern.slf4j.Slf4j;

/**
 * The payload store of every JDBC-backed outbox VanillaBP ships: the gruelbox store on
 * Spring Boot and the own store of each platform write into the same table with the same
 * statements, and only the way a connection joins the running transaction differs
 * between the two platforms ({@link JdbcConnectionAccess}).
 * <p>
 * One row per phase-two call which carries a payload, written in the transaction which
 * writes the outbox entry and read once per dispatch attempt of that entry. The row is
 * removed when the entry was dispatched and again with the dispatched entry itself;
 * {@link #removeOrphansOlderThan(Instant, int)} removes what a crash between the two
 * writes left behind, and only that.
 * <p>
 * What the store has to know for that is where the ENTRIES are and how one of them says
 * which payload it carries ({@link EntriesNamingTheirPayload}). It is a constructor
 * argument rather than something handed in per call, because it is a property of the
 * outbox this store belongs to and does not change while the application runs.
 * <p>
 * The table is VanillaBP's own, so it is described in
 * <code>io.vanillabp:vanillabp-schema</code> like the other two and created at startup
 * where the application does not manage its schema itself.
 */
@Slf4j
public class JdbcPhaseTwoPayloadStore implements PhaseTwoPayloadStore {

  /**
   * Where the entries of one outbox lie and how one of them says which payload it
   * carries. The housekeeping turns this into one condition inside its delete, so the
   * question which payloads are still needed is answered by the database and no set of
   * references travels through the application.
   *
   * @param table The table the outbox entries lie in
   * @param column The column of that table carrying the reference
   * @param wholeColumn Whether the column holds the reference and nothing else. Where it
   *        does, the condition is an equality and an index over the column answers it.
   *        Where it does not, the reference stands somewhere inside a column of text and
   *        the condition is a <code>LIKE</code>, which no index reaches - that is a scan
   *        of the entries per payload row, and it is what an outbox whose table VanillaBP
   *        does not own pays
   */
  public record EntriesNamingTheirPayload(String table, String column, boolean wholeColumn) {

    /**
     * Entries which keep the reference in a column of their own.
     *
     * @param table The table the entries lie in
     * @param column The column holding the reference
     * @return The description to build a payload store with
     */
    public static EntriesNamingTheirPayload inAColumn(
        final String table,
        final String column) {

      return new EntriesNamingTheirPayload(table, column, true);

    }

    /**
     * Entries which keep the reference somewhere inside a column of text - the
     * serialized arguments of the call, or the serialized invocation of a library
     * which owns the table.
     *
     * @param table The table the entries lie in
     * @param column The column of text the reference stands in
     * @return The description to build a payload store with
     */
    public static EntriesNamingTheirPayload insideAText(
        final String table,
        final String column) {

      return new EntriesNamingTheirPayload(table, column, false);

    }

  }

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

  /**
   * The index the housekeeping reads along. Without it the question which payloads are
   * old enough reads every payload ever written, which is the cost this table can least
   * afford.
   */
  public static final String CREATE_AGE_INDEX = "CREATE INDEX %s_AGE ON %s (CREATED_AT)";

  private final JdbcConnectionAccess connectionAccess;

  private final String tableName;

  private final EntriesNamingTheirPayload entries;

  private final String insertPayload;

  private final String selectPayload;

  private final String deletePayload;

  /**
   * The bounded delete of the orphans, built on the first housekeeping run of this
   * store. It needs the database product, which is read from a connection, so it cannot
   * be built in the constructor next to the other three.
   */
  private String deleteOrphans;

  /**
   * How many rows {@link #deleteOrphans} was built for. The bound is part of the
   * statement on every database, so a run with another ceiling builds it again.
   */
  private int deleteOrphansBoundedAt;

  /**
   * Builds the payload store of one outbox: the statements for the table it was given,
   * and where its entries say which payload they carry. The table itself is created by
   * the dispatcher of that outbox.
   *
   * @param connectionAccess How this platform hands out a connection taking part in the
   *        transaction currently running
   * @param tableName The table to store payloads in
   * @param entries Where the entries of this outbox lie and how one of them names its
   *        payload
   */
  public JdbcPhaseTwoPayloadStore(
      final JdbcConnectionAccess connectionAccess,
      final String tableName,
      final EntriesNamingTheirPayload entries) {

    this.connectionAccess = connectionAccess;
    this.tableName = tableName;
    this.entries = entries;
    this.insertPayload = INSERT_PAYLOAD.formatted(tableName);
    this.selectPayload = SELECT_PAYLOAD.formatted(tableName);
    this.deletePayload = DELETE_PAYLOAD.formatted(tableName);

  }

  /**
   * The table this store was built for. The store itself formats its statements with that
   * name; it is asked from outside where something writes into the table directly, which
   * a test ageing a payload does.
   *
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

  /**
   * {@inheritDoc}
   * <p>
   * One statement, on one connection, whatever the table holds. It deletes the payloads
   * which are old enough and which no entry of the outbox names, and the question about
   * the entries is a condition inside it rather than a question asked back - so nothing
   * of the two sets travels through the application, and the pool never waits for a
   * connection this method is holding itself.
   * <p>
   * Where the entries keep the reference inside a column of text the condition is a
   * <code>LIKE</code> with the reference in the middle. That is a scan of the entries
   * per candidate row and it is the expensive shape; what it buys is that this remains
   * one command. A reference which stands inside some other value of the same text is
   * read as "still named", and a payload kept one run too long is the harmless direction
   * of that mistake.
   */
  @Override
  public int removeOrphansOlderThan(
      final Instant threshold,
      final int maxEntries) {

    if (maxEntries < 1) {
      return 0;
    }
    Connection connection = null;
    try {
      connection = connectionAccess.acquire();
      try (var statement = connection.prepareStatement(deleteOrphans(connection, maxEntries))) {
        statement.setTimestamp(1, Timestamp.from(threshold));
        final var removed = statement.executeUpdate();
        logRemovedOrphans(removed);
        return removed;
      }
    } catch (final SQLException e) {
      log.warn("Could not remove the orphaned payloads of table '{}'", tableName, e);
      return 0;
    } finally {
      release(connection);
    }

  }

  /**
   * The delete of at most so many orphans, built once per ceiling and kept afterwards.
   *
   * @param connection The connection, read for the database product
   * @param maxEntries The most rows the statement may remove
   * @return The statement to run
   */
  private synchronized String deleteOrphans(
      final Connection connection,
      final int maxEntries) throws SQLException {

    if ((deleteOrphans != null) && (deleteOrphansBoundedAt == maxEntries)) {
      return deleteOrphans;
    }
    final var dialect = JdbcDialect.of(connection);
    final var namedByAnEntry = entries.wholeColumn()
        ? "ENTRY.%s = PAYLOAD.REFERENCE".formatted(entries.column())
        : "ENTRY.%s LIKE %s".formatted(entries.column(), dialect.anywhereInside("PAYLOAD.REFERENCE"));
    final var orphans = dialect
        .selectAtMost(
            "PAYLOAD.REFERENCE",
            "FROM %s PAYLOAD".formatted(tableName),
            "PAYLOAD.CREATED_AT < ? AND NOT EXISTS (SELECT 1 FROM %s ENTRY WHERE %s)"
                .formatted(entries.table(), namedByAnEntry),
            maxEntries);
    deleteOrphans = dialect.deleteWhatWasPicked(tableName, "REFERENCE", orphans);
    deleteOrphansBoundedAt = maxEntries;
    return deleteOrphans;

  }

  /**
   * Says that bytes were thrown away, at DEBUG and only when there were any. An orphan is a
   * payload whose outbox entry never reached the table, so nothing was lost by removing it, and
   * the normal count is zero. Somebody who finds payloads growing wants to see this line, and
   * nobody else does.
   *
   * @param removed How many payloads went
   */
  private void logRemovedOrphans(
      final int removed) {

    if (removed == 0) {
      return;
    }
    log
        .debug(
            "Removed {} payload(s) from table '{}' which no outbox entry names any more",
            removed,
            tableName);

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
              '%s' to 'false' and manage the schema manually if the DDL \
              is not suitable for your database."""
              .formatted(tableName, PhaseTwoOutboxProperties.CREATE_SCHEMA_PROPERTY), e);
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
              - let VanillaBP create the table by setting '%s' to \
              'true' (the default)."""
              .formatted(tableName, PhaseTwoOutboxProperties.CREATE_SCHEMA_PROPERTY));
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
