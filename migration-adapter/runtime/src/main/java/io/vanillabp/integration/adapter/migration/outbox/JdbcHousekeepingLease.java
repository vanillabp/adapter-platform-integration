package io.vanillabp.integration.adapter.migration.outbox;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema;
import lombok.extern.slf4j.Slf4j;

/**
 * Which node cleans up a relational outbox store tonight, with one row per store.
 * <p>
 * Every JDBC-based outbox which VanillaBP ships uses it: the store of each platform, and the
 * gruelbox store on Spring Boot. The row is keyed by the store's name. So a database with
 * two outboxes has two rows, and different nodes may clean up both at the same time. The
 * numbers which a housekeeping measures belong to one store.
 * <p>
 * It is a table of its own and not a row in the outbox. A claim is neither an entry nor a
 * payload. Gruelbox owns its table, so a row could not go there at all. And hiding the claim
 * in the table of another store would put the housekeeping of every store into the way of
 * that store's own reads. Decision 91 in the repository's DECISIONS.md says why the claim is
 * not renewed while the work runs.
 * <p>
 * The claim is an optimistic <code>UPDATE</code>. Exactly one node's write matches, and
 * every other node gets no as the answer. The row is inserted the first time a store is
 * cleaned up. If two nodes do that at the same moment, the primary key sorts it out.
 */
@Slf4j
public class JdbcHousekeepingLease {

  /**
   * Takes a claim which has run out, or which nobody holds. A claim is given back when the
   * window closes. So the moment usually matters only after a node died inside a window.
   */
  private static final String CLAIM = """
      UPDATE %s SET LEASED_BY = ?, LEASED_UNTIL = ? \
      WHERE STORE = ? AND (LEASED_UNTIL IS NULL OR LEASED_UNTIL <= ?)""";

  /**
   * Writes the row of a store which was never cleaned up before. The row carries the claim
   * right away, so the node which inserts it holds the store.
   */
  private static final String CLAIM_FIRST_TIME = """
      INSERT INTO %s (STORE, LEASED_BY, LEASED_UNTIL) VALUES (?, ?, ?)""";

  private static final String RELEASE = """
      UPDATE %s SET LEASED_BY = NULL, LEASED_UNTIL = NULL WHERE STORE = ? AND LEASED_BY = ?""";

  private final JdbcConnectionAccess connections;

  private final String tableName;

  /**
   * Builds the claim of the stores in one database.
   *
   * @param connections How this platform hands out a connection
   * @param tableName The table with the claims
   *          (<code>vanillabp.outbox.jdbc.housekeeping-table</code>)
   */
  public JdbcHousekeepingLease(
      final JdbcConnectionAccess connections,
      final String tableName) {

    this.connections = connections;
    this.tableName = tableName;

  }

  /**
   * The table this lease was built for. A test which reads the claim directly asks for
   * it.
   *
   * @return The table with the claims
   */
  public String getTableName() {

    return tableName;

  }

  /**
   * Claims a store until a moment.
   *
   * @param store The store to claim
   * @param owner Which node is claiming
   * @param until When the claim runs out by itself
   * @return Whether this node holds the store now
   */
  public boolean claimUntil(
      final String store,
      final String owner,
      final Instant until) {

    Connection connection = null;
    try {
      connection = connections.acquire();
      try (var statement = connection.prepareStatement(CLAIM.formatted(tableName))) {
        statement.setString(1, owner);
        statement.setTimestamp(2, Timestamp.from(until));
        statement.setString(3, store);
        statement.setTimestamp(4, Timestamp.from(Instant.now()));
        if (statement.executeUpdate() > 0) {
          return true;
        }
      }
      return claimFirstTime(connection, store, owner, until);
    } catch (final SQLException e) {
      // then nothing is cleaned up. That costs one night, and the meters show it. Cleaning
      // up without a claim would cost the measurement every night after it
      log.warn("Could not claim the housekeeping of the outbox store '{}'", store, e);
      return false;
    } finally {
      release(connection);
    }

  }

  /**
   * Writes the row of a store which nobody has cleaned up yet.
   *
   * @param connection The connection to write on
   * @param store The store to claim
   * @param owner Which node is claiming
   * @param until When the claim runs out
   * @return Whether the row was written. It is <code>false</code> where somebody else wrote
   *         it first. Then they hold the store
   */
  private boolean claimFirstTime(
      final Connection connection,
      final String store,
      final String owner,
      final Instant until) {

    try (var statement = connection.prepareStatement(CLAIM_FIRST_TIME.formatted(tableName))) {
      statement.setString(1, store);
      statement.setString(2, owner);
      statement.setTimestamp(3, Timestamp.from(until));
      return statement.executeUpdate() > 0;
    } catch (final SQLException e) {
      // the row is there. So the update above found it held by somebody else
      return false;
    }

  }

  /**
   * Gives a claim back. A node which does not hold the store writes nothing. The condition
   * on the owner is there for that.
   *
   * @param store The store to release
   * @param owner The node which claimed
   */
  public void release(
      final String store,
      final String owner) {

    Connection connection = null;
    try {
      connection = connections.acquire();
      try (var statement = connection.prepareStatement(RELEASE.formatted(tableName))) {
        statement.setString(1, store);
        statement.setString(2, owner);
        statement.executeUpdate();
      }
    } catch (final SQLException e) {
      // the claim runs out by itself, so the next window is free anyway
      log.debug("Could not release the housekeeping claim of the outbox store '{}'", store, e);
    } finally {
      release(connection);
    }

  }

  /**
   * Creates the table unless it exists already.
   *
   * @throws IllegalStateException If the DDL fails. The message names the way out (manage
   *           the schema manually)
   */
  public void createSchemaIfNotExists() {

    Connection connection = null;
    try {
      connection = connections.acquire();
      if (JdbcSchema.tableExists(connection, tableName)) {
        return;
      }
      try (var statement = connection.createStatement()) {
        statement.executeUpdate(buildCreateTable(connection, tableName));
      }
    } catch (final SQLException e) {
      if (createdConcurrently()) {
        return;
      }
      throw new IllegalStateException(
          """
              Could not create the housekeeping table '%s'! Set \
              '%s' to 'false' and manage the schema manually if the DDL \
              is not suitable for your database."""
              .formatted(tableName, PhaseTwoOutboxProperties.CREATE_SCHEMA_PROPERTY), e);
    } finally {
      release(connection);
    }

  }

  /**
   * Whether the DDL failed because another instance created the table between the check and
   * the statement. Two instances which start together both see no table, and both create it.
   * The boot of the loser must not end because of that.
   *
   * @return Whether the table is there now
   */
  private boolean createdConcurrently() {

    Connection connection = null;
    try {
      connection = connections.acquire();
      if (!JdbcSchema.tableExistsQuietly(connection, tableName)) {
        return false;
      }
      log.debug("The housekeeping table '{}' was created by another instance starting at the same moment", tableName);
      return true;
    } catch (final SQLException e) {
      return false;
    } finally {
      release(connection);
    }

  }

  /**
   * Checks that the table exists, for an application which creates its schema itself.
   * Otherwise a missing table would show up in the first housekeeping window, in the middle
   * of the night.
   *
   * @throws IllegalStateException If the table is missing
   */
  public void validateSchemaExists() {

    Connection connection = null;
    try {
      connection = connections.acquire();
      if (JdbcSchema.tableExists(connection, tableName)) {
        return;
      }
      throw new IllegalStateException(
          """
              The housekeeping table '%s' does not exist! One row of it says which node is removing \
              the entries and payloads of an outbox store tonight, so without the table no node \
              house-keeps and both tables grow. Either
              - apply the schema of VanillaBP with your migration tool: the artifact \
              'io.vanillabp:vanillabp-schema' ships the Liquibase changelog \
              'vanillabp/schema/changelog.xml' and the SQL generated from it for Flyway, or
              - let VanillaBP create the table by setting '%s' to \
              'true' (the default)."""
              .formatted(tableName, PhaseTwoOutboxProperties.CREATE_SCHEMA_PROPERTY));
    } catch (final SQLException e) {
      throw new IllegalStateException(
          "Could not check whether the housekeeping table '%s' exists!".formatted(tableName), e);
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
      connections.release(connection);
    } catch (final SQLException e) {
      log.warn("Could not release the connection used for the housekeeping table '{}'", tableName, e);
    }

  }

  /**
   * Builds the CREATE TABLE statement with the timestamp type of the database at hand. The
   * <code>TIMESTAMP</code> of SQL Server is a row version, and the one of MySQL ends in
   * 2038.
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
    if (product.contains("microsoft")) {
      timestampType = "DATETIME2";
    } else if (product.contains("mysql") || product.contains("mariadb")) {
      timestampType = "DATETIME(6)";
    } else {
      timestampType = "TIMESTAMP";
    }
    return """
        CREATE TABLE %s (\
        STORE VARCHAR(255) PRIMARY KEY, \
        LEASED_BY VARCHAR(255), \
        LEASED_UNTIL %s)"""
        .formatted(tableName, timestampType);

  }

}
