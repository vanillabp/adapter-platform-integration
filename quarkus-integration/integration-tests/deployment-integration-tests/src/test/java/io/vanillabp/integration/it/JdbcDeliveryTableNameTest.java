package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.jdbc.JdbcSchema;
import io.vanillabp.integration.runtime.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.activation.ActivationAggregate;
import io.vanillabp.integration.test.activation.ActivationAggregatePersistence;
import io.vanillabp.integration.test.activation.ActivationAwarenessSource;
import io.vanillabp.integration.test.activation.ActivationProcessWiringSource;
import io.vanillabp.integration.test.activation.ActivationWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * An application which has naming rules for its tables renames the delivery log the way it
 * renames the outbox: <code>vanillabp.outbox.jdbc.delivery-table</code>.
 * <p>
 * The name is followed rather than read back: the table has to be created under the
 * configured name, the record has to land in it, and the default name must not exist at
 * all. A wiring which read the property and then wrote to the constant would pass a test
 * asserting the property and fail this one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class JdbcDeliveryTableNameTest {

  private static final String RENAMED = "OUR_DELIVERIES";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("delivery-table/application.yaml", "application.yaml")
          .addClass(ActivationAggregate.class)
          .addClass(ActivationAggregatePersistence.class)
          .addClass(ActivationWorkflowService.class)
          .addClass(ActivationProcessWiringSource.class)
          .addClass(ActivationAwarenessSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/delivery-table/ActivationProcess.bpmn")
          .addAsResource("delivery-table/workflow-module", "META-INF/workflow-module"));

  @Inject
  JdbcTaskDeliveryLog deliveryLog;

  @Inject
  DataSource dataSource;

  @Inject
  UserTransaction userTransaction;

  @Test
  @DisplayName("The record lands in the configured table, and the default one is never created")
  public void theRecordLandsInTheConfiguredTable() throws Exception {

    userTransaction.begin();
    assertTrue(
        deliveryLog
            .record(
                new TaskDelivery(
                    "job-1", "demo1", "delivery-table-module", "ActivationProcess", "4711", "workflow-4711", "processTask", "Activity_processTask", null, "COMPLETED", null, null, Instant
                        .now(), null)));
    userTransaction.commit();

    try (var connection = dataSource.getConnection()) {
      assertTrue(
          JdbcSchema.tableExists(connection, RENAMED),
          "the startup creates the table the application named");
      assertFalse(
          JdbcSchema.tableExists(connection, JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
          "and nothing creates the default one beside it");
      try (var statement = connection.prepareStatement("SELECT COUNT(*) FROM %s".formatted(RENAMED))) {
        try (var rows = statement.executeQuery()) {
          assertTrue(rows.next());
          assertEquals(1, rows.getInt(1), "the record lies where the application asked for it");
        }
      }
    }

    assertTrue(
        deliveryLog
            .recordedDelivery("job-1")
            .isPresent(),
        "and the log reads it back out of the same table");

  }

}
