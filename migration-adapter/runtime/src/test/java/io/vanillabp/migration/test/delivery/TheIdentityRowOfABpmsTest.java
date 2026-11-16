package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The row in which the startup check writes down which installation of a BPMS an adapter met.
 * It shares the table with the deliveries, so the SQL which keeps it apart from them is
 * pinned here, on H2: no retention deletes it, no question about open work counts it, and it
 * does not count as a record of its adapter.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheIdentityRowOfABpmsTest {

  private static final String TABLE = "VANILLABP_TASK_DELIVERY";

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "c8";

  private final JdbcTaskDeliveryStore store;

  /**
   * The store behind the interface, so the default read of the identity row is under test.
   */
  private final TaskDeliveryLog testee;

  public TheIdentityRowOfABpmsTest() {

    final var url = "jdbc:h2:mem:bpms-identity-%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID());
    final JdbcConnectionAccess connections = () -> DriverManager.getConnection(url, "sa", "");
    this.store = new JdbcTaskDeliveryStore(connections, TABLE);
    store.createSchemaIfNotExists();
    this.testee = new TaskDeliveryLog() {

      @Override
      public java.util.Optional<TaskDelivery> recordedDelivery(
          final String deliveryKey) {

        return store.recordedDelivery(deliveryKey);

      }

      @Override
      public boolean record(
          final TaskDelivery delivery) {

        return store.record(delivery);

      }

    };

  }

  private TaskDelivery identity(
      final String identity,
      final Instant recordedAt) {

    return TaskDelivery.bpmsIdentity(ADAPTER, MODULE, PROCESS, identity, recordedAt);

  }

  @Test
  @DisplayName("The identity is read back by its key, and a second write does not replace it")
  public void theIdentityIsReadBackByItsKey() {

    assertTrue(testee.bpmsIdentityOf(ADAPTER, MODULE, PROCESS).isEmpty(), "nothing was written down yet");

    assertTrue(testee.record(identity("cluster-1", Instant.now())));
    assertFalse(testee.record(identity("cluster-2", Instant.now())), "the first row stays");

    assertEquals("cluster-1", testee.bpmsIdentityOf(ADAPTER, MODULE, PROCESS).orElseThrow().workflowId());
    assertTrue(testee.bpmsIdentityOf("another-adapter", MODULE, PROCESS).isEmpty(), "one row per adapter");

  }

  @Test
  @DisplayName("Replacing the identity moves the row to the new BPMS")
  public void replacingTheIdentityMovesTheRow() {

    assertFalse(store.replaceBpmsIdentity(identity("cluster-2", Instant.now())), "there is no row to replace yet");

    store.record(identity("cluster-1", Instant.now()));
    assertTrue(store.replaceBpmsIdentity(identity("cluster-2", Instant.now())));

    assertEquals("cluster-2", testee.bpmsIdentityOf(ADAPTER, MODULE, PROCESS).orElseThrow().workflowId());

  }

  @Test
  @DisplayName("No retention deletes the identity, however old it is")
  public void noRetentionDeletesTheIdentity() {

    store.record(identity("cluster-1", Instant.now().minus(Duration.ofDays(400))));

    store.deleteExpired(Duration.ofDays(7));
    store.deleteExpiredWorkflowStarts(Duration.ofDays(7), null);

    assertEquals("cluster-1", testee.bpmsIdentityOf(ADAPTER, MODULE, PROCESS).orElseThrow().workflowId());

  }

  @Test
  @DisplayName("The identity row is no record of its adapter and no open work")
  public void theIdentityRowIsNoRecordOfItsAdapter() {

    store.record(identity("cluster-1", Instant.now()));

    assertEquals(Boolean.FALSE, store.holdsRecordsOf(ADAPTER, MODULE, PROCESS));
    assertEquals(Boolean.FALSE, store.hasOpenRecords(MODULE, PROCESS));
    assertTrue(store.adapterIdsOfOpenTasks(MODULE, PROCESS).isEmpty());

    store
        .record(
            new TaskDelivery(
                "c8|test-module|TestProcess|CREATED|job-1", ADAPTER, MODULE, PROCESS, "4711", "instance-1", "awaitSignature", null, "job-1", "COMPLETED", null, null, Instant
                    .now(), null));
    assertEquals(Boolean.TRUE, store.holdsRecordsOf(ADAPTER, MODULE, PROCESS), "a finished task counts as well");
    assertEquals(Boolean.FALSE, store.holdsRecordsOf("another-adapter", MODULE, PROCESS));
    assertEquals(Boolean.FALSE, store.holdsRecordsOf(ADAPTER, MODULE, "AnotherProcess"));

  }

  @Test
  @DisplayName("The start of a workflow counts as a record of its adapter")
  public void aWorkflowStartIsARecordOfItsAdapter() {

    store
        .recordWorkflowStart(
            TaskDelivery.workflowStart(ADAPTER, MODULE, PROCESS, "4711", "instance-1", Instant.now()));

    assertEquals(Boolean.TRUE, store.holdsRecordsOf(ADAPTER, MODULE, PROCESS));

  }

}
