package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.sql.SQLException;
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
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.jdbc.UtcTimestamps;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoBpmsBusy;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * An entry whose BPMS refuses it because it is busy waits for the BPMS, and uses no attempt
 * for it. A Camunda 8 cluster under load refuses commands with backpressure for seconds or
 * minutes. If each refusal counted, an entry which is fine would end up blocked only because
 * the cluster was full.
 * <p>
 * The tests below hold this on the relational store of the core: the refusals use no
 * attempts and know no time limit, and each of them writes a WARN line. The feature tests of
 * both platforms show the same through the BPMS double.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
public class ABusyBpmsUsesNoAttemptsTest {

  /**
   * The attempt frequency of the store here. It is short, so many refusals fit into a test.
   */
  private static final Duration ATTEMPT_FREQUENCY = Duration.ofMillis(100);

  /**
   * How many times the BPMS says it is busy. This is more than the attempts the store allows.
   * A store which counted them would block the entry before the BPMS has room again.
   */
  private static final int REFUSALS_BEFORE_THE_BPMS_HAS_ROOM = 6;

  /**
   * The attempts the store allows. Two, so counting the refusals above would block the entry
   * three times over.
   */
  private static final int BLOCK_AFTER_ATTEMPTS = 2;

  /**
   * How long a test waits for something it expects to happen. It guards against a machine
   * which leaves the JVM without a turn. It does not measure speed.
   */
  private static final Duration UNTIL_IT_HAPPENED = Duration.ofSeconds(30);

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "77";

  private static final String INSERT_ENTRY = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, \
      IDEMPOTENCY_KEY, DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) \
      VALUES (?, '%s', '%s', ?, '%s', 'dummy', NULL, NULL, ?, '%s', ?, 0, ?)""";

  private static final String SELECT_ENTRY = "SELECT STATUS, ATTEMPTS FROM %s WHERE ID = ?";

  @Mock
  private MigrationProcessService<Object> processService;

  /**
   * How often the adapter was asked. That says how often the entry came back.
   */
  private final AtomicInteger callsIntoTheAdapter = new AtomicInteger();

  private record Entry(String status, int attempts) {
  }

  private static JdbcConnectionAccess databaseNamed(
      final String name) {

    return () -> DriverManager.getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  /**
   * The settings of a store which allows two attempts and waits for a read model one hour at
   * most. That limit must not end the wait for a busy BPMS.
   */
  private static PhaseTwoOutboxProperties allowingTwoAttempts() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setBlockAfterAttempts(BLOCK_AFTER_ATTEMPTS);
    properties.setAttemptFrequency(ATTEMPT_FREQUENCY);
    properties.setWaitForVisibilityAtMost(Duration.ofHours(1));
    properties.setPollInterval(ATTEMPT_FREQUENCY);
    return properties;

  }

  /**
   * A router whose only process service says that its BPMS is busy the given number of times,
   * and accepts the call afterwards.
   */
  private PhaseTwoRouter aRouterWhoseBpmsHasRoomAfter(
      final int refusals) {

    when(processService.getWorkflowModuleId()).thenReturn(MODULE);
    when(processService.getBpmnProcessId()).thenReturn(PROCESS);
    when(processService.convertAggregateId(AGGREGATE)).thenReturn(AGGREGATE);
    Mockito
        .doAnswer(invocation -> {
          if (callsIntoTheAdapter.incrementAndGet() <= refusals) {
            throw new PhaseTwoBpmsBusy(
                "Phase two of a test operation was refused by adapter 'dummy', because its BPMS reports backpressure: full", new IllegalStateException("full"));
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

  private static JdbcPhaseTwoOutboxDispatcher dispatcherOf(
      final JdbcConnectionAccess connections,
      final PhaseTwoRouter router,
      final String table) {

    return new JdbcPhaseTwoOutboxDispatcher(
        connections, allowingTwoAttempts(), table, new JdbcPhaseTwoPayloadStore(connections, table + JdbcPhaseTwoPayloadStore.TABLE_NAME_SUFFIX, JdbcPhaseTwoOutboxStore
            .entriesNamingTheirPayload(table)), () -> router, () -> VanillaBpMetrics.NONE, "JdbcPhaseTwoOutbox");

  }

  private static String anEntryWrittenAt(
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
      UtcTimestamps.write(statement, 4, writtenAt);
      UtcTimestamps.write(statement, 5, Instant.now());
      statement.executeUpdate();
    }
    return id;

  }

  private static Entry entryOf(
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
  @DisplayName("Refusals of a busy BPMS use no attempts and know no time limit, and each of them is a WARN")
  public void refusalsOfABusyBpmsUseNoAttempts() throws Exception {

    final var table = "OUTBOX_BUSY_BPMS_USES_NO_ATTEMPTS";
    final var connections = databaseNamed("busy-bpms-uses-no-attempts");
    final var dispatcher = dispatcherOf(connections, aRouterWhoseBpmsHasRoomAfter(REFUSALS_BEFORE_THE_BPMS_HAS_ROOM),
        table);
    dispatcher.prepareSchema();
    // written two hours ago, which is longer than a read model may keep it waiting. A busy
    // BPMS is no read model, so this limit does not end its wait
    final var entry = anEntryWrittenAt(connections, table, Instant.now().minus(Duration.ofHours(2)));

    // the test logging of this module has no appender, so the lines are caught here
    final var lines = new ListAppender<ILoggingEvent>();
    lines.start();
    final var dispatcherLog = (Logger) LoggerFactory.getLogger(JdbcPhaseTwoOutboxDispatcher.class);
    dispatcherLog.addAppender(lines);
    try {
      dispatcher.start();
      waitUntil(
          "the entry was not dispatched after the BPMS had room again",
          () -> JdbcPhaseTwoOutboxDispatcher.STATUS_DONE.equals(entryOf(connections, table, entry).status()));
    } finally {
      dispatcher.stop();
      dispatcherLog.detachAppender(lines);
      lines.stop();
    }

    assertEquals(
        REFUSALS_BEFORE_THE_BPMS_HAS_ROOM + 1,
        callsIntoTheAdapter.get(),
        "the entry came back after every refusal and went through once the BPMS had room");
    assertEquals(
        1,
        entryOf(connections, table, entry).attempts(),
        "only the attempt which went through is counted, the refusals are not");
    final var busyLines = lines.list
        .stream()
        .filter(line -> line.getFormattedMessage().contains("met a busy BPMS"))
        .toList();
    assertEquals(REFUSALS_BEFORE_THE_BPMS_HAS_ROOM, busyLines.size(), "one line per refusal: "
        + busyLines);
    assertTrue(
        busyLines
            .stream()
            .allMatch(line -> (line.getLevel() == Level.WARN) && line
                .getFormattedMessage()
                .contains("adapter 'dummy'") && line
                    .getFormattedMessage()
                    .contains("uses none of its attempts")),
        "each line is a WARN which names the adapter: "
            + busyLines);

  }

  @Test
  @DisplayName("The wait for a busy BPMS is the attempt frequency, spread by a fifth in both directions")
  public void theWaitIsTheAttemptFrequencySpread() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setAttemptFrequency(Duration.ofSeconds(30));

    for (var round = 0; round < 1_000; ++round) {
      final var wait = properties.delayForABusyBpms();
      assertTrue(
          (wait.compareTo(Duration.ofSeconds(24)) >= 0) && (wait.compareTo(Duration.ofSeconds(36)) <= 0),
          "out of the spread: "
              + wait);
    }

  }

}
