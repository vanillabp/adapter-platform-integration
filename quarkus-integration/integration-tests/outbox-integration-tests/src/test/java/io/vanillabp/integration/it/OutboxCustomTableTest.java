package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Store-name configuration of the JDBC default outbox (story 26i): with
 * <code>vanillabp.outbox.jdbc.table</code> set, the outbox stores (and the
 * dispatcher polls) the configured table - the default table is never created.
 * Every outbox instance needs its own store, so a dedicated outbox for a high-load
 * process gets its own table this way.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxCustomTableTest {

  private static final String CUSTOM_TABLE = "CUSTOM_HOT_OUTBOX";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("vanillabp.outbox.jdbc.table", CUSTOM_TABLE)
      // separate database: the module's other tests share the class-level H2 URL
      .overrideConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:outbox-custom-table-it;DB_CLOSE_DELAY=-1");

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  private long count(
      final String table) throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .createStatement(); var resultSet = statement.executeQuery("SELECT COUNT(*) FROM "
            + table)) {
      resultSet.next();
      return resultSet.getLong(1);
    }

  }

  private boolean tableExists(
      final String table) throws Exception {

    try (var connection = dataSource.getConnection(); var tables = connection
        .getMetaData()
        .getTables(null, null, table, new String[]{
            "TABLE"
        })) {
      return tables.next();
    }

  }

  @Test
  @DisplayName("Entries are stored in and dispatched from the configured table only")
  public void entriesUseTheConfiguredTable() throws Exception {

    listener.reset();

    userTransaction.begin();
    final Aggregate attachedAggregate;
    try {
      attachedAggregate = workflowService.startWorkflow("custom-table-test");
      assertEquals(1, count(CUSTOM_TABLE));
    } catch (Exception e) {
      userTransaction.rollback();
      throw e;
    }
    userTransaction.commit();
    assertNotNull(attachedAggregate.getId());

    // dispatched from the configured table
    final var invocations = listener.awaitInvocations(1, 10000);
    assertEquals(attachedAggregate.getId(), invocations.getFirst());

    // the default table was never created
    assertFalse(tableExists("VANILLABP_PHASE_TWO_OUTBOX"));

  }

}
