package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoRetryLater;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * An entry whose BPMS does not report its workflow yet waits for time, not for attempts.
 * <p>
 * Measured against a Camunda 8 cluster whose exporter was paused: the adapter answered every
 * dispatch with a window of ten seconds, each answer counted an attempt, and after fifty of
 * them the entry was blocked. That was eight minutes, and the entries did not come back by
 * themselves once the exporter caught up. A database which was away for the same eight
 * minutes blocked nothing, because the growing backoff spreads fifty attempts over four
 * hours.
 * <p>
 * The tests below hold the two halves on the relational store: the answers use no attempts
 * however many there are, and the entry is blocked once
 * <code>vanillabp.outbox.wait-for-visibility-at-most</code> passed since it was written.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
public class WaitingForAReadModelUsesNoAttemptsTest {

  /**
   * The window the adapter names here. Short, so many answers fit into a test, and the same
   * on every answer, the way an adapter names it.
   */
  private static final Duration WINDOW = Duration.ofMillis(100);

  /**
   * How many times the adapter says "not yet" in the first test. More than the attempts the
   * store allows, so a store which counted them would block the entry before the workflow
   * shows up.
   */
  private static final int ANSWERS_BEFORE_THE_WORKFLOW_SHOWS_UP = 6;

  /**
   * The attempts the store allows. Two, so counting the answers above would block the entry
   * three times over.
   */
  private static final int BLOCK_AFTER_ATTEMPTS = 2;

  /**
   * How long a test waits for something it expects to happen. It is a guard against a machine
   * which leaves the JVM without a turn, not a measurement of speed.
   */
  private static final Duration UNTIL_IT_HAPPENED = Duration.ofSeconds(30);

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "76";

  private static final String INSERT_ENTRY = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, \
      IDEMPOTENCY_KEY, DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) \
      VALUES (?, '%s', '%s', ?, '%s', 'dummy', NULL, NULL, ?, '%s', ?, 0, ?)""";

  private static final String SELECT_ENTRY = "SELECT STATUS, ATTEMPTS FROM %s WHERE ID = ?";

  @Mock
  private MigrationProcessService<Object> processService;

  /**
   * How often the adapter was asked, which is what says how often the entry came back.
   */
  private final AtomicInteger callsIntoTheAdapter = new AtomicInteger();

  private JdbcConnectionAccess databaseNamed(
      final String name) {

    return () -> DriverManager.getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  /**
   * The settings of a store which allows two attempts and waits for a read model as long as
   * given. The poll interval is short, so the entry is picked up soon after each window.
   */
  private static PhaseTwoOutboxProperties allowingTwoAttemptsAndWaiting(
      final Duration waitAtMost) {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setBlockAfterAttempts(BLOCK_AFTER_ATTEMPTS);
    properties.setWaitForVisibilityAtMost(waitAtMost);
    properties.setPollInterval(WINDOW);
    return properties;

  }

  /**
   * A router whose only process service answers "not yet" the given number of times and
   * accepts the call afterwards.
   *
   * @param answersNotYet How many dispatches are answered with the window first
   */
  private PhaseTwoRouter aRouterWhoseReadModelCatchesUpAfter(
      final int answersNotYet) {

    when(processService.getWorkflowModuleId()).thenReturn(MODULE);
    when(processService.getBpmnProcessId()).thenReturn(PROCESS);
    when(processService.convertAggregateId(AGGREGATE)).thenReturn(AGGREGATE);
    Mockito
        .doAnswer(invocation -> {
          if (callsIntoTheAdapter.incrementAndGet() <= answersNotYet) {
            throw new PhaseTwoRetryLater("the BPMS does not report the workflow yet", WINDOW);
          }
          return null;
        })
        .when(processService)
        .executePhaseTwo(
            Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
    final var router = new PhaseTwoRouter();
    router.register(processService);
    return router;

  }

  private JdbcPhaseTwoOutboxDispatcher dispatcherOf(
      final JdbcConnectionAccess connections,
      final PhaseTwoOutboxProperties properties,
      final PhaseTwoRouter router,
      final String table) {

    return new JdbcPhaseTwoOutboxDispatcher(
        connections, properties, table, new JdbcPhaseTwoPayloadStore(connections, table + JdbcPhaseTwoPayloadStore.TABLE_NAME_SUFFIX, JdbcPhaseTwoOutboxStore
            .entriesNamingTheirPayload(table)), () -> router, () -> VanillaBpMetrics.NONE, "JdbcPhaseTwoOutbox");

  }

  /**
   * Writes an entry which is due now.
   *
   * @param connections The database to write into
   * @param table The outbox table
   * @param writtenAt When the entry claims to have been written
   * @return The entry's id
   */
  private String anEntryWrittenAt(
      final JdbcConnectionAccess connections,
      final String table,
      final Instant writtenAt) throws SQLException {

    final var id = UUID.randomUUID().toString();
    try (var connection = connections.acquire(); var statement = connection
        .prepareStatement(
            INSERT_ENTRY
                .formatted(table, MODULE, PROCESS, AGGREGATE, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN))) {
      statement.setString(1, id);
      statement.setString(2, PhaseOperation.CORRELATE_MESSAGE.name());
      statement.setString(3, id);
      statement.setTimestamp(4, Timestamp.from(writtenAt));
      statement.setTimestamp(5, Timestamp.from(Instant.now()));
      statement.executeUpdate();
    }
    return id;

  }

  private Entry entryOf(
      final JdbcConnectionAccess connections,
      final String table,
      final String id) throws SQLException {

    try (var connection = connections.acquire(); var statement = connection
        .prepareStatement(SELECT_ENTRY.formatted(table))) {
      statement.setString(1, id);
      try (var resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next(), "the entry is gone");
        return new Entry(resultSet.getString(1), resultSet.getInt(2));
      }
    }

  }

  private record Entry(String status, int attempts) {
  }

  private static void waitUntil(
      final String whatDidNotHappen,
      final java.util.concurrent.Callable<Boolean> itHappened) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED.toMillis();
    while (!itHappened.call()) {
      assertTrue(System.currentTimeMillis() < deadline, whatDidNotHappen);
      Thread.sleep(20);
    }

  }

  @Test
  @DisplayName("Answers of a read model which is behind use no attempts, however many there are")
  public void answersOfALaggingReadModelUseNoAttempts() throws Exception {

    final var table = "OUTBOX_READ_MODEL_USES_NO_ATTEMPTS";
    final var connections = databaseNamed("read-model-uses-no-attempts");
    final var dispatcher = dispatcherOf(
        connections, allowingTwoAttemptsAndWaiting(Duration.ofHours(1)), aRouterWhoseReadModelCatchesUpAfter(
            ANSWERS_BEFORE_THE_WORKFLOW_SHOWS_UP),
        table);
    dispatcher.prepareSchema();
    final var entry = anEntryWrittenAt(connections, table, Instant.now());

    try {
      dispatcher.start();
      waitUntil(
          "the entry was not dispatched after the read model caught up",
          () -> JdbcPhaseTwoOutboxDispatcher.STATUS_DONE.equals(entryOf(connections, table, entry).status()));
    } finally {
      dispatcher.stop();
    }

    assertEquals(
        ANSWERS_BEFORE_THE_WORKFLOW_SHOWS_UP + 1,
        callsIntoTheAdapter.get(),
        "the entry came back after every window and went through once the read model caught up");
    assertEquals(
        1,
        entryOf(connections, table, entry).attempts(),
        "only the attempt which went through is counted, the answers 'not yet' are not");

  }

  @Test
  @DisplayName("An entry which waited longer than wait-for-visibility-at-most is blocked")
  public void anEntryWhichWaitedTooLongIsBlocked() throws Exception {

    final var table = "OUTBOX_READ_MODEL_WAITED_TOO_LONG";
    final var connections = databaseNamed("read-model-waited-too-long");
    final var dispatcher = dispatcherOf(
        connections, allowingTwoAttemptsAndWaiting(Duration.ofHours(1)), aRouterWhoseReadModelCatchesUpAfter(
            Integer.MAX_VALUE),
        table);
    dispatcher.prepareSchema();
    // written two hours ago and never dispatched, which is what an exporter which stopped
    // for two hours leaves behind
    final var entry = anEntryWrittenAt(connections, table, Instant.now().minus(Duration.ofHours(2)));

    try {
      dispatcher.start();
      waitUntil(
          "the entry which waited too long was not blocked",
          () -> JdbcPhaseTwoOutboxDispatcher.STATUS_BLOCKED.equals(entryOf(connections, table, entry).status()));
      // long enough for several more windows: a blocked entry is read by no poll
      Thread.sleep(5 * WINDOW.toMillis());
    } finally {
      dispatcher.stop();
    }

    assertEquals(
        1,
        callsIntoTheAdapter.get(),
        "the first answer 'not yet' after the time ran out blocks the entry, and nothing asks again");

  }

}
