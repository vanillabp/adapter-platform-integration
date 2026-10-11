package io.vanillabp.integration.adapter.migration.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Reads the database's own catalog. Every store uses it which creates its table or checks that
 * the application created it. Examples are
 * {@link io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore} and the
 * phase-two outboxes of both platform integrations.
 * <p>
 * VanillaBP ships one schema artifact for the four tables it owns, and the startup check looks
 * at the columns and not only at the table. Decision 16 in the repository's DECISIONS.md says
 * why.
 */
public final class JdbcSchema {

  private JdbcSchema() {
  }

  /**
   * Whether a table of that name exists. It asks the JDBC metadata and does not use
   * <code>CREATE TABLE IF NOT EXISTS</code>, because not every database supports that (for
   * example Oracle and SQL Server).
   *
   * @param connection The connection to the database holding the table
   * @param tableName The table to look for, spelled as the application configured it
   * @return Whether the table exists
   * @throws SQLException If the metadata cannot be read
   */
  public static boolean tableExists(
      final Connection connection,
      final String tableName) throws SQLException {

    final var metaData = connection.getMetaData();
    // some databases (Oracle, H2) turn unquoted identifiers into upper case, others
    // (PostgreSQL) into lower case, so check both spellings
    for (final var name : List.of(
        tableName,
        tableName.toLowerCase())) {
      try (var tables = metaData.getTables(null, null, name, new String[]{
          "TABLE"
      })) {
        if (tables.next()) {
          return true;
        }
      }
    }
    return false;

  }

  /**
   * Whether a column of that name exists in a table. Like
   * {@link #tableExists(Connection, String)}, it asks the JDBC metadata. It is used where an
   * earlier version of VanillaBP created the table, or where the application provided it.
   * The table alone does not prove that everything the current version writes has a place.
   * A missing column would show up at the first delivery and not at startup.
   *
   * @param connection The connection to the database holding the table
   * @param tableName The table, spelled as the application configured it
   * @param columnName The column to look for
   * @return Whether the column exists
   * @throws SQLException If the metadata cannot be read
   */
  public static boolean columnExists(
      final Connection connection,
      final String tableName,
      final String columnName) throws SQLException {

    final var metaData = connection.getMetaData();
    // some databases turn unquoted identifiers into upper case, others into lower case
    for (final var table : List.of(
        tableName,
        tableName.toLowerCase())) {
      for (final var column : List.of(
          columnName,
          columnName.toLowerCase())) {
        try (var columns = metaData.getColumns(null, null, table, column)) {
          if (columns.next()) {
            return true;
          }
        }
      }
    }
    return false;

  }

  /**
   * Whether an index of that name exists on a table. It asks the JDBC metadata. It is used where
   * an earlier version of VanillaBP created the table. Then the index which a later version reads
   * by is missing, and the application has to run the statement which adds it.
   * <p>
   * If the database cannot answer the question, it counts as having the index. A warning about a
   * warning would confuse more than it helps, because nobody could act on it.
   *
   * @param connection The connection to the database holding the table
   * @param tableName The table, spelled as the application configured it
   * @param indexName The index to look for
   * @return Whether the index exists
   */
  public static boolean indexExists(
      final Connection connection,
      final String tableName,
      final String indexName) {

    try {
      final var metaData = connection.getMetaData();
      // some databases turn unquoted identifiers into upper case, others into lower case
      for (final var table : List.of(
          tableName,
          tableName.toLowerCase())) {
        try (var indexes = metaData.getIndexInfo(null, null, table, false, true)) {
          while (indexes.next()) {
            final var found = indexes.getString("INDEX_NAME");
            if ((found != null) && found.equalsIgnoreCase(indexName)) {
              return true;
            }
          }
        }
      }
      return false;
    } catch (final SQLException e) {
      return true;
    }

  }

  /**
   * Whether the table exists, without letting the question itself fail. It is asked after the
   * database refused a <code>CREATE TABLE</code>. Two instances may start at the same moment, for
   * example in a rolling deployment or a scale-up from zero. Both see no table and both run the
   * DDL. The loser gets "table already exists", and that is no failure of its deployment. Each
   * database product reports a different error for this. So instead of guessing an SQL state,
   * the metadata question is asked once more.
   * <p>
   * Ask it through a CONNECTION OF ITS OWN. Where the pool does not commit each statement by
   * itself, the failed DDL left the current connection in an aborted transaction. Every further
   * question on that connection would fail as well.
   *
   * @param connection A connection of its own, not the one the DDL failed on
   * @param tableName The table to look for
   * @return Whether the table exists now; <code>false</code> also when the metadata cannot be read
   */
  public static boolean tableExistsQuietly(
      final Connection connection,
      final String tableName) {

    try {
      return tableExists(connection, tableName);
    } catch (final SQLException e) {
      return false;
    }

  }

}
