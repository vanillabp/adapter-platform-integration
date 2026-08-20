package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Story 75: an application which creates its schema with Liquibase or Flyway switches VanillaBP's
 * table creation off. A missing table is then a deployment which forgot to apply the migration, and
 * that has to be said at startup - not at the first delivery, hours later.
 * <p>
 * Story 97 added a column to the table, which is the case the check of story 75 did not catch: a
 * table created by an earlier version of VanillaBP exists, so the check passed and the missing
 * column surfaced at the first delivery. The columns added later are therefore verified as well,
 * whether the application hands the schema over or lets VanillaBP create it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class JdbcTaskDeliverySchemaTest {

  private static JdbcConnectionAccess h2(
      final String name) {

    return () -> DriverManager
        .getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  private static JdbcTaskDeliveryStore storeOn(
      final String database) {

    return new JdbcTaskDeliveryStore(h2(database), "VANILLABP_TASK_DELIVERY");

  }

  @Test
  @DisplayName("A missing table ends the startup naming the table, the property and the artifact")
  public void aMissingTableIsReportedAtStartup() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> storeOn("missing").validateSchemaExists());

    assertTrue(failure.getMessage().contains("VANILLABP_TASK_DELIVERY"), failure.getMessage());
    assertTrue(failure.getMessage().contains("vanillabp.outbox.create-schema"), failure.getMessage());
    assertTrue(failure.getMessage().contains("io.vanillabp:vanillabp-schema"), failure.getMessage());
    assertTrue(
        failure.getMessage().contains("vanillabp/schema/changelog.xml"),
        failure.getMessage());

  }

  @Test
  @DisplayName("With the table in place the check passes - and the runtime's own DDL satisfies it")
  public void anExistingTablePasses() {

    final var store = storeOn("created");
    store.createSchemaIfNotExists();

    assertDoesNotThrow(store::validateSchemaExists);

  }

  @Test
  @DisplayName("A table created by the shipped changelog satisfies the check as well")
  public void aTableOfTheChangelogPasses() throws SQLException {

    // the columns the changelog of vanillabp-schema creates, spelled out here because the core must
    // not depend on that artifact - if the two ever diverge, this test and the schema module's own
    // test disagree, which is the point
    try (Connection connection = h2("changelog").acquire(); var statement = connection.createStatement()) {
      statement
          .executeUpdate(
              """
                  CREATE TABLE VANILLABP_TASK_DELIVERY (\
                  DELIVERY_KEY VARCHAR(512) NOT NULL, \
                  WORKFLOW_MODULE_ID VARCHAR(255) NOT NULL, \
                  BPMN_PROCESS_ID VARCHAR(255) NOT NULL, \
                  AGGREGATE_ID VARCHAR(1024), \
                  TASK_DEFINITION VARCHAR(255), \
                  OUTCOME VARCHAR(32) NOT NULL, \
                  BPMN_ERROR_CODE VARCHAR(255), \
                  BPMN_ERROR_NAME VARCHAR(255), \
                  RECORDED_AT TIMESTAMP NOT NULL, \
                  LAST_SEEN_AT TIMESTAMP NOT NULL, \
                  CONSTRAINT PK_VANILLABP_TASK_DELIVERY PRIMARY KEY (DELIVERY_KEY))""");
    }

    assertDoesNotThrow(() -> storeOn("changelog").validateSchemaExists());

  }

  /**
   * The table as VanillaBP created it before the second timestamp existed - the case a check
   * looking at tables only cannot see.
   */
  private static void createTableOfAnEarlierVersion(
      final String database) throws SQLException {

    try (Connection connection = h2(database).acquire(); var statement = connection.createStatement()) {
      statement
          .executeUpdate(
              """
                  CREATE TABLE VANILLABP_TASK_DELIVERY (\
                  DELIVERY_KEY VARCHAR(512) PRIMARY KEY, \
                  WORKFLOW_MODULE_ID VARCHAR(255) NOT NULL, \
                  BPMN_PROCESS_ID VARCHAR(255) NOT NULL, \
                  AGGREGATE_ID VARCHAR(1024), \
                  TASK_DEFINITION VARCHAR(255), \
                  OUTCOME VARCHAR(32) NOT NULL, \
                  BPMN_ERROR_CODE VARCHAR(255), \
                  BPMN_ERROR_NAME VARCHAR(255), \
                  RECORDED_AT TIMESTAMP NOT NULL)""");
    }

  }

  @Test
  @DisplayName("A table of an earlier version ends the startup naming the column and the way to add it")
  public void aMissingColumnIsReportedAtStartup() throws SQLException {

    createTableOfAnEarlierVersion("outdated");

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> storeOn("outdated").validateSchemaExists());

    assertTrue(failure.getMessage().contains("LAST_SEEN_AT"), failure.getMessage());
    assertTrue(failure.getMessage().contains("ALTER TABLE VANILLABP_TASK_DELIVERY"), failure.getMessage());
    assertTrue(failure.getMessage().contains("io.vanillabp:vanillabp-schema"), failure.getMessage());

  }

  @Test
  @DisplayName("Even where VanillaBP creates the schema itself, a table of an earlier version is named")
  public void aMissingColumnIsReportedWhereTheTableIsCreated() throws SQLException {

    createTableOfAnEarlierVersion("outdated-created");

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> storeOn("outdated-created").createSchemaIfNotExists());

    assertTrue(failure.getMessage().contains("LAST_SEEN_AT"), failure.getMessage());

  }

}
