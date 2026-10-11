package io.vanillabp.integration.adapter.migration.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * The few places where the stores of VanillaBP cannot write one statement for every
 * database.
 * <p>
 * Most of what the stores do is plain SQL. The DDL of each store already picks its types
 * per database product. An upper limit on the rows a statement works on needs the same
 * care, because there is no portable way to write it. PostgreSQL, MySQL and H2 take a
 * <code>LIMIT</code>, SQL Server takes a <code>TOP</code>, and Oracle takes a condition on
 * <code>ROWNUM</code>. A <code>DELETE</code> takes none of the three. So a limited delete
 * deletes the keys which a limited select returned.
 * <p>
 * The same is true for putting the value of a column into the middle of a pattern. Three
 * databases write that in three ways. A store needs it to find an identifier inside a
 * column of text.
 * <p>
 * The dialect is read from the connection once for each statement a store builds. The DDL
 * reads it the same way.
 */
public enum JdbcDialect {

  /**
   * Oracle, which bounds rows with <code>ROWNUM</code> and concatenates with
   * <code>||</code>.
   */
  ORACLE,

  /**
   * Microsoft SQL Server, which bounds rows with <code>TOP</code> and concatenates
   * with <code>+</code>.
   */
  SQL_SERVER,

  /**
   * MySQL and MariaDB, which bound rows with <code>LIMIT</code> and concatenate with
   * <code>CONCAT</code>.
   */
  MYSQL,

  /**
   * Everything else, which is PostgreSQL, H2 and the databases spelling both the same
   * way: <code>LIMIT</code> and <code>||</code>.
   */
  OTHER;

  /**
   * Which of the four the database at hand is.
   *
   * @param connection The connection to read the product name from
   * @return The dialect, {@link #OTHER} for a product none of the special cases names
   * @throws SQLException If the metadata cannot be read
   */
  public static JdbcDialect of(
      final Connection connection) throws SQLException {

    final var product = connection
        .getMetaData()
        .getDatabaseProductName()
        .toLowerCase(Locale.ROOT);
    if (product.contains("oracle")) {
      return ORACLE;
    }
    if (product.contains("microsoft")) {
      return SQL_SERVER;
    }
    if (product.contains("mysql") || product.contains("mariadb")) {
      return MYSQL;
    }
    return OTHER;

  }

  /**
   * Limits a select to a number of rows. It does not say WHICH rows, and the callers do not
   * care. They delete a batch and come back for the next one. So any rows will do, as long
   * as there are not too many at once.
   *
   * @param columns What the select reads, without the <code>SELECT</code> keyword
   * @param fromAndJoins The tables it reads from, from the <code>FROM</code> keyword
   *          onwards
   * @param where The condition, without the <code>WHERE</code> keyword
   * @param maxRows The most rows the statement may return
   * @return The bounded select
   */
  public String selectAtMost(
      final String columns,
      final String fromAndJoins,
      final String where,
      final int maxRows) {

    return switch (this) {
      case ORACLE -> "SELECT %s %s WHERE %s AND ROWNUM <= %d"
          .formatted(columns, fromAndJoins, where, maxRows);
      case SQL_SERVER -> "SELECT TOP %d %s %s WHERE %s"
          .formatted(maxRows, columns, fromAndJoins, where);
      default -> "SELECT %s %s WHERE %s LIMIT %d"
          .formatted(columns, fromAndJoins, where, maxRows);
    };

  }

  /**
   * Deletes the rows which a limited select picked. None of the three ways to limit rows is
   * allowed in a delete, so the delete uses the select.
   * <p>
   * The select is wrapped in a table of its own, although nothing reads two columns from it.
   * MySQL refuses a subquery which reads the table the delete works on, but it accepts a
   * derived table. The alias has no <code>AS</code>, because Oracle does not allow it for a
   * table.
   *
   * @param table The table to delete from
   * @param keyColumn The column the picked rows are addressed by
   * @param boundedSelect A select reading that column, as
   *          {@link #selectAtMost(String, String, String, int)} built it
   * @return The bounded delete
   */
  public String deleteWhatWasPicked(
      final String table,
      final String keyColumn,
      final String boundedSelect) {

    return "DELETE FROM %s WHERE %s IN (SELECT %s FROM (%s) PICKED)"
        .formatted(table, keyColumn, keyColumn, boundedSelect);

  }

  /**
   * The pattern which matches a text that contains the value of a column anywhere.
   * <p>
   * It is an expression and not a parameter of the statement. The value is a column of the
   * row being read, and a prepared statement cannot take a column as a parameter.
   *
   * @param column The column holding the value to look for, qualified by its table
   *          alias
   * @return The expression to put right of a <code>LIKE</code>
   */
  public String anywhereInside(
      final String column) {

    return switch (this) {
      case SQL_SERVER -> "'%%' + %s + '%%'".formatted(column);
      case MYSQL -> "CONCAT('%%', %s, '%%')".formatted(column);
      default -> "'%%' || %s || '%%'".formatted(column);
    };

  }

}
