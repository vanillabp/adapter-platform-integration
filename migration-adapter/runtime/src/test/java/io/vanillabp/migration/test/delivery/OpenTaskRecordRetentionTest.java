package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.delivery.OpenTaskTouches;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Story 97: the record of a task which is still open outlives the retention, the record of
 * one nobody redelivers any more does not. Both are the JDBC store's business, which is
 * shared by the two platforms, so this is where the SQL is pinned - on H2, with records
 * backdated instead of waited for.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OpenTaskRecordRetentionTest {

  private static final String TABLE = "VANILLABP_TASK_DELIVERY";

  private static JdbcConnectionAccess h2(
      final String name) {

    return () -> DriverManager
        .getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  private final JdbcConnectionAccess connections;

  private final JdbcTaskDeliveryStore testee;

  public OpenTaskRecordRetentionTest() {

    this.connections = h2("open-task-"
        + java.util.UUID.randomUUID());
    this.testee = new JdbcTaskDeliveryStore(connections, TABLE);
    testee.createSchemaIfNotExists();

  }

  /**
   * A record written the given time ago - both of its timestamps, exactly as the insert
   * writes them.
   */
  private void record(
      final String deliveryKey,
      final String outcome,
      final Duration age) {

    testee.record(
        new TaskDelivery(
            deliveryKey, "adapter", "test-module", "TestProcess", "4711", "awaitCompletion", outcome, null, null, Instant
                .now()
                .minus(age)));

  }

  /**
   * A record of the given adapter, written just now.
   */
  private void recordOf(
      final String adapterId,
      final String deliveryKey,
      final String outcome) {

    testee
        .record(
            new TaskDelivery(
                deliveryKey, adapterId, "test-module", "TestProcess", "4711", "awaitCompletion", outcome, null, null, Instant
                    .now()));

  }

  @org.junit.jupiter.api.Test
  @DisplayName("The adapter ids of OPEN records are answered, per workflow module and process")
  public void openRecordsAnswerTheirAdapterIds() {

    recordOf("old-bpms", "open-of-old", "COMPLETION_PENDING");
    recordOf("new-bpms", "open-of-new", "COMPLETION_PENDING");
    // a completed task is no leftover: nothing is redelivered for it any more
    recordOf("done-bpms", "completed", "COMPLETED");
    // a record written before the column existed has no adapter id and is no answer
    testee
        .record(
            new TaskDelivery(
                "open-without-adapter", null, "test-module", "TestProcess", "4711", "awaitCompletion", "COMPLETION_PENDING", null, null, Instant
                    .now()));

    assertEquals(
        java.util.Set.of("old-bpms", "new-bpms"),
        testee.adapterIdsOfOpenTasks("test-module", "TestProcess"));
    assertEquals(
        java.util.Set.of(),
        testee.adapterIdsOfOpenTasks("other-module", "TestProcess"),
        "another workflow module is another question");
    assertEquals(
        java.util.Set.of(),
        testee.adapterIdsOfOpenTasks("test-module", "OtherProcess"),
        "another BPMN process is another question");

  }

  private boolean exists(
      final String deliveryKey) {

    return testee.recordedDelivery(deliveryKey).isPresent();

  }

  /**
   * The two timestamps of a record, read past the store - which maps only the one the
   * core knows about.
   */
  private java.sql.Timestamp[] timestampsOf(
      final String deliveryKey) throws SQLException {

    try (var connection = connections.acquire(); var statement = connection
        .prepareStatement("SELECT RECORDED_AT, LAST_SEEN_AT FROM %s WHERE DELIVERY_KEY = ?"
            .formatted(TABLE))) {
      statement.setString(1, deliveryKey);
      try (var resultSet = statement.executeQuery()) {
        resultSet.next();
        return new java.sql.Timestamp[]{
            resultSet.getTimestamp(1), resultSet.getTimestamp(2)
        };
      }
    }

  }

  @Test
  @DisplayName("A record is written seen at the moment it was recorded")
  public void aNewRecordWasSeenWhenItWasWritten() throws SQLException {

    record("job-1", "COMPLETION_PENDING", Duration.ZERO);

    final var timestamps = timestampsOf("job-1");
    assertEquals(timestamps[0], timestamps[1], "nothing was redelivered yet");

  }

  @Test
  @DisplayName("A redelivered open task keeps its record, one nobody redelivers loses it")
  public void aRedeliveredOpenTaskKeepsItsRecord() throws SQLException {

    record("job-open", "COMPLETION_PENDING", Duration.ofHours(2));
    record("job-forgotten", "COMPLETION_PENDING", Duration.ofHours(2));
    record("job-done", "COMPLETED", Duration.ofHours(2));

    // the BPMS redelivered the open task, which is what the core reports to the store
    testee.stillOpen("job-open");

    final var deleted = testee.deleteExpired(Duration.ofHours(1));

    assertEquals(2, deleted);
    assertTrue(exists("job-open"), "a task which is still being redelivered keeps the record answering it");
    assertFalse(exists("job-forgotten"), "a record nobody has seen for a whole retention expires");
    assertFalse(exists("job-done"), "and so does the record of a task which is done");

    final var timestamps = timestampsOf("job-open");
    assertTrue(
        timestamps[1].after(timestamps[0]),
        "the moment it was last seen moved, the moment it was recorded did not");
    assertTrue(
        timestamps[0].toInstant().isBefore(Instant.now().minus(Duration.ofMinutes(90))),
        "so the age of the open task is still measured from the moment the handler ran");

  }

  @Test
  @DisplayName("An open task whose record expired anyway is recorded again on the next delivery")
  public void anExpiredRecordIsWrittenAgain() {

    record("job-lost", "COMPLETION_PENDING", Duration.ofHours(2));
    assertEquals(1, testee.deleteExpired(Duration.ofHours(1)));

    // the key of a record which is gone updates nothing, and that must not throw
    testee.stillOpen("job-lost");
    assertEquals(1, testee.refreshOpenTasks());
    assertFalse(exists("job-lost"));

  }

  @Test
  @DisplayName("More open tasks than one block are refreshed in blocks")
  public void moreOpenTasksThanOneBlockAreRefreshed() throws SQLException {

    final var tasks = OpenTaskTouches.BLOCK_SIZE + 100;
    for (var i = 0; i < tasks; i++) {
      record("job-"
          + i, "COMPLETION_PENDING", Duration.ofHours(2));
      testee.stillOpen("job-"
          + i);
    }

    assertEquals(tasks, testee.refreshOpenTasks());
    assertEquals(0, testee.deleteExpired(Duration.ofHours(1)), "every one of them survives");

    final var timestamps = timestampsOf("job-"
        + (tasks - 1));
    assertTrue(timestamps[1].after(timestamps[0]), "the last key of the last block was written as well");

  }

}
