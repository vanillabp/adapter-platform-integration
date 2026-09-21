package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.runtime.outbox.JdbcPhaseTwoOutbox;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * How old the oldest entry waiting in the JDBC outbox is. It is the number which tells
 * a backlog being worked off from one standing still, and the two entries of this test
 * have different ages on purpose: an answer which reported the youngest one would look
 * healthy while the oldest operation is the one nobody carried out.
 * <p>
 * The entries are written into the table directly and dated into the future, so the
 * dispatcher leaves them alone and they stay what the test needs them to be: entries
 * which wait.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxOldestPendingAgeTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(TestMeterRegistryProducer.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // the gauge has to show what was just written, so nothing is held between reads
      .overrideRuntimeConfigKey("vanillabp.metrics.gauge-cache", "PT0S")
      // separate database: the module's other tests share the class-level H2 URL
      .overrideRuntimeConfigKey(
          "quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:outbox-oldest-pending-age-it;DB_CLOSE_DELAY=-1");

  @Inject
  DataSource dataSource;

  @Inject
  JdbcPhaseTwoOutbox outbox;

  @Inject
  SimpleMeterRegistry meterRegistry;

  @Test
  @DisplayName("The age is the one of the oldest waiting entry, not of the youngest")
  public void theOldestWaitingEntryDecidesTheAge() throws Exception {

    final var age = meterRegistry
        .get(VanillaBpMetrics.OUTBOX_OLDEST_PENDING_AGE)
        .tag(VanillaBpMetrics.TAG_STORE, JdbcPhaseTwoOutbox.class.getSimpleName())
        .gauge();
    assertEquals(
        0.0,
        age.value(),
        "nothing waits yet, and that zero says the outbox owes nothing");

    final var now = Instant.now();
    writeWaitingEntry("younger", now.minus(Duration.ofMinutes(1)));
    writeWaitingEntry("older", now.minus(Duration.ofMinutes(10)));

    assertTrue(
        outbox
            .ageOfOldestPendingCall()
            .orElseThrow()
            .toSeconds() >= 600L,
        "the store answers for the entry which has been waiting longest");
    assertTrue(
        age.value() >= 600.0,
        "and the gauge publishes that age in seconds");
    assertTrue(
        age.value() < 3_600.0,
        "a gauge reading far above the oldest entry would be a clock, not an age");

  }

  /**
   * Writes an entry which stays where it is: its next attempt lies an hour ahead, so
   * the dispatcher's poll does not pick it up.
   *
   * @param id The id of the entry
   * @param writtenAt When it was written
   */
  private void writeWaitingEntry(
      final String id,
      final Instant writtenAt) throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .prepareStatement("""
            INSERT INTO VANILLABP_PHASE_TWO_OUTBOX \
            (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, DEDUP_KEY, STATUS, \
            CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) \
            VALUES (?, 'test-module', 'Test', 'START_WORKFLOW', '4711', ?, 'OPEN', ?, 0, ?)""")) {
      statement.setString(1, id);
      statement.setString(2, id);
      statement.setTimestamp(3, Timestamp.from(writtenAt));
      statement.setTimestamp(4, Timestamp.from(Instant.now().plus(Duration.ofHours(1))));
      statement.executeUpdate();
    }

  }

}
