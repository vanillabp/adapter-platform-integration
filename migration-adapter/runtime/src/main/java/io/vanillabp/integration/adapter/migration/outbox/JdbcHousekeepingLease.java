package io.vanillabp.integration.adapter.migration.outbox;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema;
import lombok.extern.slf4j.Slf4j;

/**
 * Which node house-keeps a relational outbox store tonight, one row per store.
 * <p>
 * Every JDBC-backed outbox VanillaBP ships uses it: the store of each platform and the
 * gruelbox store on Spring Boot. The row is keyed by the store's name, so a database
 * carrying two outboxes carries two rows and both may be house-kept by different nodes at
 * the same time - the numbers a housekeeping measures belong to one store.
 * <p>
 * A table of its own and not a row in the outbox: a claim is neither an entry nor a
 * payload, gruelbox owns its table so a row could not go there at all, and the alternative
 * of hiding the claim in another store's table would put the housekeeping of every store
 * into the path of that store's own reads. Why the claim is not renewed while the work
 * runs is decision 91 in the repository's DECISIONS.md.
 * <p>
 * The claim is an optimistic <code>UPDATE</code>: exactly one node's write matches, and
 * every other node is answered no. The row is inserted the first time a store is
 * house-kept, and two nodes doing that at the same moment are sorted out by the primary
 * key.
 */
@Slf4j
public class JdbcHousekeepingLease {

  /**
   * Takes a claim which has run out or which nobody holds. A claim is given back when the
   * window closes, so the moment normally matters only after a node died inside one.
   */
  private static final String CLAIM = """
      UPDATE %s SET LEASED_BY = ?, LEASED_UNTIL = ? \
      WHERE STORE = ? AND (LEASED_UNTIL IS NULL OR LEASED_UNTIL <= ?)""";

  /**
   * Writes the row of a store which was never house-kept before. It carries the claim
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
   * @param tableName The table the claims lie in
   *          (<code>vanillabp.outbox.jdbc.housekeeping-table</code>)
   */
  public JdbcHousekeepingLease(
      final JdbcConnectionAccess connections,
      final String tableName) {

    this.connections = connections;
    this.tableName = tableName;

  }

  /**
   * The table this lease was built for, asked from outside where a test reads the claim
   * directly.
   *
   * @return The table the claims lie in
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
      // nothing is house-kept then, which costs one night and is visible in the meters.
      // House-keeping without a claim would cost the measurement every night after it
      log.warn("Could not claim the housekeeping of the outbox store '{}'", store, e);
      return false;
    } finally {
      release(connection);
    }

  }

  /**
   * Writes the row of a store nobody has house-kept yet.
   *
   * @param connection The connection to write on
   * @param store The store to claim
   * @param owner Which node is claiming
   * @param until When the claim runs out
   * @return Whether the row was written, <code>false</code> where somebody else wrote it
   *         first - which is them holding the store
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
      // the row is there, which means the update above found it held by somebody else
      return false;
    }

  }

  /**
   * Gives a claim back. A node which does not hold the store writes nothing, which is
   * what the condition over the owner is for.
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
      // the claim runs out by itself, so the next window is free either way
      log.debug("Could not release the housekeeping claim of the outbox store '{}'", store, e);
    } finally {
      release(connection);
    }

  }

  /**
   * Creates the table unless it exists already.
   *
   * @throws IllegalStateException If the DDL fails - naming the way out (manage the
   *           schema manually)
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
              'vanillabp.outbox.create-schema' to 'false' and manage the schema manually if the DDL \
              is not suitable for your database."""
              .formatted(tableName), e);
    } finally {
      release(connection);
    }

  }

  /**
   * Whether the DDL failed because another instance created the table between the check
   * and the statement. Two instances starting together both see no table and both create
   * it, and the loser's boot must not end over it.
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
   * Verifies that the table exists, for an application which creates its schema itself. A
   * missing table would otherwise surface in the first housekeeping window, in the middle
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
              - let VanillaBP create the table by setting 'vanillabp.outbox.create-schema' to \
              'true' (the default)."""
              .formatted(tableName));
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
   * Builds the CREATE TABLE statement with the timestamp type the database at hand
   * spells: SQL Server's <code>TIMESTAMP</code> is a row version and MySQL's ends in
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
