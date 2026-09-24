package io.vanillabp.schema;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.schema.ChangelogDescription;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;

/**
 * The changelog applied by Liquibase itself, on H2 - one of the two databases this module
 * promises. What is asserted is not "it ran" but that the tables VanillaBP writes into exist with
 * the columns the runtime would have created, because an application may start with the runtime's
 * tables and switch to this changelog later.
 *
 * <p>
 * Which tables, columns and indexes those are is not typed out here. {@link ChangelogDescription}
 * asks the changelog, and the assertions compare its answer with the database. A list in the test
 * would only ever catch what disappeared: a table newly arrived would read as green, which is how
 * the payload table of the phase-two outbox went unnoticed for months. The one list left is
 * {@link #TABLES_OF_VANILLABP}, and it exists to be compared, not to be trusted.
 * </p>
 */
@ExtendWith(SuppressOutputExtension.class)
public class ChangelogAppliesTest {

  /**
   * The tables this module promises, and the only place in its test code which names them.
   * <code>README.md</code> names them too, and so do the wiki pages of the two platform
   * integrations. They are compared against the changelog rather than trusted, so the day a
   * changeset brings a fourth table, {@link #theChangelogDescribesTheTablesThisModulePromises()}
   * fails and whoever takes the new name over writes it into those documents as well.
   */
  private static final Set<String> TABLES_OF_VANILLABP = Set
      .of(
          "VANILLABP_PHASE_TWO_OUTBOX",
          "VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD",
          "VANILLABP_TASK_DELIVERY");

  private static Connection h2(
      final String name) throws Exception {

    return DriverManager.getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  /**
   * Applies the changelog to a fresh in-memory database and returns a connection to it. Liquibase
   * closes the connection it was handed, so the assertions get one of their own - the database
   * survives thanks to <code>DB_CLOSE_DELAY=-1</code>.
   */
  private static Connection applyTo(
      final String name,
      final Map<String, String> changelogProperties) throws Exception {

    try (var connection = h2(name)) {
      update(connection, changelogProperties);
    }
    return h2(name);

  }

  private static void update(
      final Connection connection,
      final Map<String, String> changelogProperties) throws Exception {

    final var database = DatabaseFactory
        .getInstance()
        .findCorrectDatabaseImplementation(new JdbcConnection(connection));
    try (var liquibase = new Liquibase(
        ChangelogDescription.CHANGELOG, new ClassLoaderResourceAccessor(), database)) {
      changelogProperties.forEach((
          key,
          value) -> liquibase.setChangeLogParameter(key, value));
      liquibase.update(new Contexts(), new LabelExpression());
    }

  }

  private static Map<String, String> columnsOf(
      final Connection connection,
      final String tableName) throws Exception {

    final var columns = new LinkedHashMap<String, String>();
    try (var resultSet = connection.getMetaData().getColumns(null, null, tableName, null)) {
      while (resultSet.next()) {
        columns.put(resultSet.getString("COLUMN_NAME"), resultSet.getString("TYPE_NAME"));
      }
    }
    return columns;

  }

  /**
   * The indexes of a table, each with the columns it spans in their order.
   */
  private static Map<String, List<String>> indexesOf(
      final Connection connection,
      final String tableName) throws Exception {

    final var indexes = new LinkedHashMap<String, List<String>>();
    try (var resultSet = connection.getMetaData().getIndexInfo(null, null, tableName, false, true)) {
      while (resultSet.next()) {
        final var name = resultSet.getString("INDEX_NAME");
        if (name == null) {
          continue;
        }
        indexes
            .computeIfAbsent(name.toUpperCase(), index -> new ArrayList<>())
            .add(resultSet.getString("COLUMN_NAME"));
      }
    }
    return indexes;

  }

  /**
   * Compares every table the changelog describes with the table of that name in the database,
   * column by column and in order.
   */
  private static void assertEveryTableIsThere(
      final Connection connection,
      final ChangelogDescription changelog) throws Exception {

    for (final var table : changelog.tables().entrySet()) {
      assertEquals(
          table.getValue(),
          List.copyOf(columnsOf(connection, table.getKey()).keySet()),
          """
              The changelog describes the table %s, so after the update the database has to hold \
              it with exactly these columns. An empty list on the right means the table is \
              missing: read the changeset which creates it, and check whether a property renamed \
              it."""
              .formatted(table.getKey()));
    }

  }

  @Test
  @DisplayName("The changelog describes the tables this module promises - no more, no fewer")
  public void theChangelogDescribesTheTablesThisModulePromises() throws Exception {

    assertEquals(
        TABLES_OF_VANILLABP,
        ChangelogDescription.of(Map.of()).tableNames(),
        """
            A changeset added or removed a table of this artifact. Take the name into \
            TABLES_OF_VANILLABP above, into schema/README.md and into the wiki pages which list \
            the tables an application has to create, so this module keeps saying what it brings. \
            The columns and indexes need no list - they are read from the changelog.""");

  }

  @Test
  @DisplayName("Every table the changelog describes is created, with the columns it describes")
  public void everyDescribedTableIsCreated() throws Exception {

    final var changelog = ChangelogDescription.of(Map.of());

    try (var connection = applyTo("changelog", Map.of())) {
      assertEveryTableIsThere(connection, changelog);
    }

  }

  @Test
  @DisplayName("Every index the changelog describes is created, over the columns it names")
  public void everyDescribedIndexIsCreated() throws Exception {

    final var changelog = ChangelogDescription.of(Map.of());

    try (var connection = applyTo("changelog-indexes", Map.of())) {
      for (final var index : changelog.indexes()) {
        final var indexes = indexesOf(connection, index.table());
        assertEquals(
            index.columns(),
            indexes.get(index.name()),
            """
                The changelog describes the index %s on %s. The stores read by these indexes, so \
                a missing one turns a query of every poll into a table scan. What the database \
                has: %s"""
                .formatted(index.name(), index.table(), indexes.keySet()));
      }
    }

  }

  @Test
  @DisplayName("A duplicate dedup key is refused - that is what makes a duplicate schedule a no-op")
  public void theDedupKeyIsUnique() throws Exception {

    try (var connection = applyTo("unique", Map.of())) {
      try (var statement = connection.createStatement()) {
        statement
            .executeUpdate(
                """
                    INSERT INTO VANILLABP_PHASE_TWO_OUTBOX \
                    (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, IDEMPOTENCY_KEY, DEDUP_KEY, \
                    STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) VALUES \
                    ('1', 'module', 'Process', 'START', 'key-1', 'key-1', 'OPEN', CURRENT_TIMESTAMP, 0, \
                    CURRENT_TIMESTAMP)""");
      }

      final var duplicate = assertThrows(
          SQLException.class,
          () -> {
            try (var statement = connection.createStatement()) {
              statement
                  .executeUpdate(
                      """
                          INSERT INTO VANILLABP_PHASE_TWO_OUTBOX \
                          (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, IDEMPOTENCY_KEY, \
                          DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) VALUES \
                          ('2', 'module', 'Process', 'START', 'key-1', 'key-1', 'OPEN', \
                          CURRENT_TIMESTAMP, 0, CURRENT_TIMESTAMP)""");
            }
          });
      assertTrue(duplicate.getMessage().toLowerCase().contains("unique"), duplicate.getMessage());
    }

  }

  @Test
  @DisplayName("A table name of the application is a changelog property, so a renamed table needs no fork")
  public void tableNamesAreProperties() throws Exception {

    final var renamed = Map
        .of(
            "vanillabp.outbox.table", "MY_OUTBOX",
            "vanillabp.delivery.table", "MY_DELIVERIES",
            "vanillabp.payload.table", "MY_PAYLOADS");

    final var changelog = ChangelogDescription.of(renamed);
    assertEquals(
        Set.of("MY_OUTBOX", "MY_DELIVERIES", "MY_PAYLOADS"),
        changelog.tableNames(),
        """
            Every table of the changelog takes its name from a property an application can \
            override. A name which stayed as it was belongs to a changeset spelling the table \
            out, and an application which renames would be left with a table nobody reads.""");

    try (var connection = applyTo("renamed", renamed)) {
      assertEveryTableIsThere(connection, changelog);
      assertTrue(
          columnsOf(connection, "VANILLABP_PHASE_TWO_OUTBOX").isEmpty(),
          "the default name must be gone once the property renamed the table");
    }

  }

}
