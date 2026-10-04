package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.spi.DeliveryRecordKind;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.WorkflowStartKey;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The version of the process definition in the SQL both platforms share, on H2: written with a
 * delivery and with the start of a workflow, read back with them, and added to the start row of a
 * workflow whose first report did not know it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class JdbcRowsCarryTheProcessVersionTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "4711";

  private static final String ADAPTER = "c8";

  private final JdbcTaskDeliveryStore store;

  public JdbcRowsCarryTheProcessVersionTest() {

    final var url = "jdbc:h2:mem:process-version-%s;DB_CLOSE_DELAY=-1".formatted(UUID.randomUUID());
    final JdbcConnectionAccess connections = () -> DriverManager.getConnection(url, "sa", "");
    this.store = new JdbcTaskDeliveryStore(connections, "VANILLABP_TASK_DELIVERY");
    store.createSchemaIfNotExists();

  }

  private boolean givenAStart(
      final String workflowId,
      final String processVersion) {

    return store
        .recordWorkflowStart(
            TaskDelivery.workflowStart(ADAPTER, MODULE, PROCESS, AGGREGATE, workflowId, processVersion, Instant.now()));

  }

  private TaskDelivery theStartRow() {

    return store.recordedDelivery(WorkflowStartKey.of(MODULE, PROCESS, AGGREGATE)).orElseThrow();

  }

  @Test
  @DisplayName("A delivery is read back with the version it was written with")
  public void aDeliveryKeepsItsVersion() {

    store
        .record(
            new TaskDelivery(
                "a-delivery", ADAPTER, MODULE, PROCESS, AGGREGATE, "instance-1", "awaitSignature", null, "job-1", "COMPLETION_PENDING", null, null, Instant
                    .now(), null, "TASK", DeliveryRecordKind.TASK_DELIVERY.name(), "7"));

    assertEquals("7", store.recordedDelivery("a-delivery").orElseThrow().processVersion());
    assertEquals(
        "7",
        store.openTasksOfAggregate(MODULE, PROCESS, AGGREGATE).getFirst().processVersion(),
        "and in the answers about open work, which every read shares");

  }

  @Test
  @DisplayName("A delivery written the way a caller wrote it before the version existed names none")
  public void aDeliveryOfAnOlderCallerNamesNoVersion() {

    store
        .record(
            new TaskDelivery(
                "a-delivery", ADAPTER, MODULE, PROCESS, AGGREGATE, "instance-1", "awaitSignature", null, "job-1", "COMPLETED", null, null, Instant
                    .now(), null, "TASK"));

    assertNull(store.recordedDelivery("a-delivery").orElseThrow().processVersion());

  }

  @Test
  @DisplayName("The start of a workflow is read back with its version")
  public void aStartKeepsItsVersion() {

    assertTrue(givenAStart("instance-1", "3"));

    assertEquals("3", theStartRow().processVersion());

  }

  @Test
  @DisplayName("A second report of the same start adds the version the first one lacked")
  public void aSecondReportAddsTheMissingVersion() {

    // the adapter started the workflow and named no version, the BPMS reported the same start
    // afterwards and knew it
    givenAStart("instance-1", null);

    assertTrue(givenAStart("instance-1", "3"), "the row holds something it did not hold before");

    assertEquals("3", theStartRow().processVersion());
    assertEquals("instance-1", theStartRow().workflowId());

  }

  @Test
  @DisplayName("A version the row holds already stays: it is the one the workflow started on")
  public void aVersionWhichIsThereStays() {

    givenAStart("instance-1", "3");

    assertFalse(givenAStart("instance-1", "4"));
    assertFalse(givenAStart("instance-1", null));

    assertEquals("3", theStartRow().processVersion());

  }

  @Test
  @DisplayName("A second workflow of one aggregate brings its own version, or none")
  public void aSecondWorkflowBringsItsOwnVersion() {

    givenAStart("instance-1", "3");

    assertTrue(givenAStart("instance-2", null));

    assertEquals("instance-2", theStartRow().workflowId());
    assertNull(
        theStartRow().processVersion(),
        "the version of the workflow which ended says nothing about the one which runs now");

    givenAStart("instance-2", "4");

    assertEquals("4", theStartRow().processVersion());

  }

}
