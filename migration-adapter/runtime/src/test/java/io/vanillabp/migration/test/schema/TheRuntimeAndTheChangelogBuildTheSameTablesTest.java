package io.vanillabp.migration.test.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.schema.ChangelogDescription;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * An application has two ways to the same tables: VanillaBP creates them while it starts,
 * or the application applies the changelog of
 * <code>io.vanillabp:vanillabp-schema</code>. The two have to end in the same table,
 * because an application may start with the first and move to the second later, and it
 * does so at the moment it is least prepared for a surprise.
 * <p>
 * The test lives here, next to the classes which write the runtime's DDL. The schema
 * module cannot hold it: it has no dependency on the runtime on purpose, and its own test
 * can therefore only compare the changelog with the database the changelog itself built.
 * Here both sides are on the classpath, the schema artifact in the test scope alone, so
 * it stays a leaf nothing ships a dependency on.
 * <p>
 * <strong>What a difference means.</strong> Neither side is the authority by itself. A
 * column the runtime creates and the changelog does not is a changeset somebody owes, and
 * a column only the changelog knows is a column the runtime forgot to add. Whoever finds
 * this test red reads the column: it belongs to the change which introduced it, and that
 * change has to reach both places.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheRuntimeAndTheChangelogBuildTheSameTablesTest {

  /**
   * What the JDBC metadata says about one column: the type the database chose for it and
   * how wide it is. Both are read from the same H2, so a difference is a difference of
   * the two DDLs and not of two databases.
   *
   * @param type The type name of the database
   * @param size How many characters or digits it holds
   * @param nullable Whether the column accepts nothing
   */
  private record Column(String type, int size, boolean nullable) {
  }

  private static Connection h2(
      final String name) throws SQLException {

    return DriverManager.getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  /**
   * The database an application gets which applies the changelog.
   *
   * @return A connection to it
   */
  private static Connection builtByTheChangelog() throws Exception {

    final var name = "schema-from-the-changelog";
    try (var connection = h2(name)) {
      final var database = DatabaseFactory
          .getInstance()
          .findCorrectDatabaseImplementation(new JdbcConnection(connection));
      try (var liquibase = new Liquibase(
          ChangelogDescription.CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
        liquibase.update(new Contexts(), new LabelExpression());
      }
    }
    return h2(name);

  }

  /**
   * The database an application gets which lets VanillaBP create its tables: the outbox
   * with the payloads beside it, and the log of the task deliveries.
   *
   * @return A connection to it
   */
  private static Connection builtByTheRuntime() throws Exception {

    final var name = "schema-from-the-runtime";
    final JdbcConnectionAccess connections = () -> h2(name);
    new JdbcPhaseTwoOutboxDispatcher(
        connections, new PhaseTwoOutboxProperties(), JdbcPhaseTwoOutboxStore.DEFAULT_TABLE_NAME, new JdbcPhaseTwoPayloadStore(connections, JdbcPhaseTwoPayloadStore.DEFAULT_TABLE_NAME, JdbcPhaseTwoOutboxStore
            .entriesNamingTheirPayload(
                JdbcPhaseTwoOutboxStore.DEFAULT_TABLE_NAME)), () -> null, () -> null, "JdbcPhaseTwoOutbox")
        .prepareSchema();
    new JdbcTaskDeliveryStore(connections, JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME)
        .createSchemaIfNotExists();
    return h2(name);

  }

  private static Map<String, Column> columnsOf(
      final Connection connection,
      final String table) throws Exception {

    final var columns = new LinkedHashMap<String, Column>();
    try (var results = connection.getMetaData().getColumns(null, null, table, null)) {
      while (results.next()) {
        columns
            .put(
                results.getString("COLUMN_NAME"),
                new Column(
                    results.getString("TYPE_NAME"), results.getInt("COLUMN_SIZE"), "YES"
                        .equals(results.getString("IS_NULLABLE"))));
      }
    }
    return columns;

  }

  @Test
  @DisplayName("Neither way builds a table the other does not know")
  public void neitherWayBuildsATableOfItsOwn() throws Exception {

    try (var fromTheChangelog = builtByTheChangelog(); var fromTheRuntime = builtByTheRuntime()) {

      assertEquals(
          vanillaBpTablesOf(fromTheChangelog),
          vanillaBpTablesOf(fromTheRuntime),
          """
              One way builds a table the other does not! A table only the runtime creates is a \
              changeset the schema artifact owes; a table only the changelog creates is one the \
              runtime stopped creating without the changelog being told.""");

    }

  }

  /**
   * @param connection The database
   * @return The tables of VanillaBP in it, which is every table this platform creates one
   *         way or the other
   */
  private static TreeSet<String> vanillaBpTablesOf(
      final Connection connection) throws Exception {

    final var tables = new TreeSet<String>();
    try (var results = connection
        .getMetaData()
        .getTables(null, "PUBLIC", "VANILLABP%", new String[]{
            "TABLE"
        })) {
      while (results.next()) {
        tables.add(results.getString("TABLE_NAME"));
      }
    }
    return tables;

  }

  /**
   * The indexes VanillaBP names itself, each with the columns it spans in their order. Only the
   * named ones: a primary key and a unique constraint bring an index whose name the database makes
   * up, and two databases would then differ over a name nobody wrote.
   *
   * @param connection The database
   * @param table The table to read them from
   * @return The indexes by name
   */
  private static Map<String, List<String>> namedIndexesOf(
      final Connection connection,
      final String table) throws Exception {

    final var indexes = new TreeMap<String, List<String>>();
    try (var results = connection.getMetaData().getIndexInfo(null, null, table, false, true)) {
      while (results.next()) {
        final var name = results.getString("INDEX_NAME");
        if ((name == null) || !name.toUpperCase().startsWith(table
            + "_")) {
          continue;
        }
        indexes.computeIfAbsent(name.toUpperCase(), index -> new ArrayList<>()).add(results.getString("COLUMN_NAME"));
      }
    }
    return indexes;

  }

  @Test
  @DisplayName("Both ways build the same indexes, under the same names and over the same columns")
  public void bothWaysBuildTheSameIndexes() throws Exception {

    try (var fromTheChangelog = builtByTheChangelog(); var fromTheRuntime = builtByTheRuntime()) {

      for (final var table : new TreeSet<>(ChangelogDescription.of(Map.of()).tableNames())) {
        // a table which is read by its primary key alone carries no index VanillaBP named,
        // and the claim of the housekeeping is one of those. That both ways build the
        // table at all is what the assertion over the columns holds
        final var built = namedIndexesOf(fromTheChangelog, table);
        assertEquals(
            built,
            namedIndexesOf(fromTheRuntime, table),
            """
                The indexes on '%s' are not the same on both ways! A store reads by the name it \
                creates, so an index the changelog builds under another name is an index that \
                store never uses, and an index only one way builds is a question which scans the \
                whole table on the other. Whoever adds an index adds it in both places, under one \
                name."""
                .formatted(table));
      }

    }

  }

  @Test
  @DisplayName("Both ways build the same tables, with the same columns and the same types")
  public void bothWaysBuildTheSameTables() throws Exception {

    try (var fromTheChangelog = builtByTheChangelog(); var fromTheRuntime = builtByTheRuntime()) {

      final var tables = new TreeSet<>(ChangelogDescription.of(Map.of()).tableNames());
      assertFalse(tables.isEmpty(), "the changelog describes no table at all");
      for (final var table : tables) {
        final var built = columnsOf(fromTheChangelog, table);
        // a table which is not there answers with no column at all, and two tables which
        // are both missing would compare as equal - which is the one way this test could
        // pass while proving nothing
        assertFalse(built.isEmpty(), "the changelog did not build '%s'".formatted(table));
        assertEquals(
            built,
            columnsOf(fromTheRuntime, table),
            """
                The table '%s' is not the same on both ways! The changelog of the schema artifact \
                builds one table and the runtime of VanillaBP builds another, so an application \
                moving from one way to the other gets a table it does not expect. Read the column \
                which differs: it belongs to the change which introduced it, and that change has \
                to reach the runtime AND the changelog."""
                .formatted(table));
      }

    }

  }

}
