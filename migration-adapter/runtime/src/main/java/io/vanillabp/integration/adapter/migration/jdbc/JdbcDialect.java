package io.vanillabp.integration.adapter.migration.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;

/**
 * The few places where the stores of VanillaBP cannot write one statement for every
 * database.
 * <p>
 * Most of what they do is plain SQL, and the DDL each store writes already picks its
 * types per database product. What needs the same treatment is an upper bound on the
 * rows a statement works on: there is no portable way to write it. PostgreSQL, MySQL
 * and H2 take a <code>LIMIT</code>, SQL Server a <code>TOP</code> and Oracle a
 * condition over <code>ROWNUM</code>, and a <code>DELETE</code> takes none of the three
 * at all, so a bounded delete is a delete over the keys a bounded select returned.
 * <p>
 * The same holds for putting the value of a column into the middle of a pattern: three
 * databases spell that in three ways, and a store looking for an identifier inside a
 * column of text needs it.
 * <p>
 * The dialect is read from the connection, once per statement a store builds, the way
 * the DDL reads it.
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
   * Bounds a select at a number of rows. WHICH rows those are is not said, and the
   * callers of this method do not care: they delete a batch and come back for the next
   * one, so any rows will do as long as there are not too many of them at once.
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
   * Deletes the rows a bounded select picked, which is how the three spellings above
   * reach a statement none of them may stand in.
   * <p>
   * The select is wrapped in a table of its own even though nothing reads two columns
   * from it: MySQL refuses a subquery reading the table the delete works on, and a
   * derived table is what it accepts instead. The alias carries no <code>AS</code>,
   * which Oracle does not allow for a table.
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
   * The pattern which matches a text carrying the value of a column anywhere inside it.
   * <p>
   * Built as an expression rather than as a parameter of the statement, because the
   * value is a column of the row being looked at and a prepared statement cannot carry
   * one of those.
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
