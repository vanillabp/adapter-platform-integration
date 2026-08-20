package io.vanillabp.integration.adapter.migration.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Reading the database's own catalog, used by every store which either creates its table
 * or verifies that the application created it (see
 * {@link io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore} and the
 * phase-two outboxes of both platform integrations).
 */
public final class JdbcSchema {

  private JdbcSchema() {
  }

  /**
   * Whether a table of that name exists, asked of the JDBC metadata rather than by a
   * <code>CREATE TABLE IF NOT EXISTS</code>, which not every database supports (e.g. Oracle,
   * SQL Server).
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
    // unquoted identifiers are folded to upper case by some databases (Oracle, H2)
    // and to lower case by others (PostgreSQL) - check both spellings
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
   * Whether a column of that name exists in a table, asked of the JDBC metadata like
   * {@link #tableExists(Connection, String)} is. Used where a table was created by an
   * earlier version of VanillaBP or handed over by the application: the table alone does
   * not prove that everything the current version writes has a place to go, and a missing
   * column would surface at the first delivery rather than at startup.
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
    // see tableExists on the spelling of unquoted identifiers
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

}
