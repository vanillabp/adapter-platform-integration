package io.vanillabp.integration.adapter.migration.jdbc;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * How the JDBC stores of VanillaBP write and read a moment. Every one of them goes through
 * here, so that all of them agree.
 * <p>
 * The columns which hold a moment are a <code>TIMESTAMP</code> without a time zone (a
 * <code>datetime(6)</code> on MySQL). Such a column holds a date and a time of day, but no
 * zone. So whoever writes it decides which zone that time of day belongs to, and whoever
 * reads it has to know the same zone. VanillaBP always writes UTC. See
 * {@code DECISIONS.pending/2217.md}.
 * <p>
 * The obvious <code>Timestamp.from(instant)</code> does something else. The driver turns
 * it into the time of day in the zone of the JVM. Two nodes in different zones then
 * disagree by hours about when an entry is due. And in the hour at the end of daylight
 * saving time, one node disagrees with itself, because that hour happens twice.
 * <p>
 * A moment travels as a {@link LocalDateTime} in UTC. JDBC 4.2 maps that type to a
 * <code>TIMESTAMP</code> without a zone, and a driver passes it on unchanged. A
 * <code>Calendar</code> passed to <code>setTimestamp</code> would do the same in theory.
 * But some drivers let a setting of the connection override it (the time zone settings of
 * MySQL Connector/J, for example).
 */
public final class UtcTimestamps {

  private UtcTimestamps() {
    // only static methods
  }

  /**
   * Writes a moment into a parameter of a statement.
   *
   * @param statement The statement
   * @param parameterIndex The parameter, counted from 1
   * @param moment The moment, or <code>null</code> for SQL <code>NULL</code>
   * @throws SQLException If the driver refuses the value
   */
  public static void write(
      final PreparedStatement statement,
      final int parameterIndex,
      final Instant moment) throws SQLException {

    if (moment == null) {
      statement.setNull(parameterIndex, Types.TIMESTAMP);
      return;
    }
    statement.setObject(parameterIndex, LocalDateTime.ofInstant(moment, ZoneOffset.UTC));

  }

  /**
   * Reads a moment which {@link #write(PreparedStatement, int, Instant)} wrote.
   *
   * @param resultSet The row being read
   * @param columnIndex The column, counted from 1
   * @return The moment, or <code>null</code> where the column is SQL <code>NULL</code>
   * @throws SQLException If the driver cannot read the column as a time of day
   */
  public static Instant read(
      final ResultSet resultSet,
      final int columnIndex) throws SQLException {

    final var written = resultSet.getObject(columnIndex, LocalDateTime.class);
    return written == null
        ? null
        : written.toInstant(ZoneOffset.UTC);

  }

}
