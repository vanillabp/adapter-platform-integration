package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
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
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * A dispatched entry gives the poller of the JDBC outbox no reason to wake up. The entry
 * stays in the table until its retention has passed. But the housekeeping deletes it in its
 * night window, and a wake-up only dispatches. So the end of the retention is not a moment
 * the poller plans.
 * <p>
 * The test counts the connections the dispatcher takes. Every statement needs one, so a
 * wake-up which asks the table anything shows up in the count. The retention is short, the
 * cap on the sleep is an hour, and the window of the housekeeping is shut while the test
 * runs. So a connection after the retention can only come from a wake-up planned for its end.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
public class ADoneEntryPlansNoWakeUpTest {

  private static final String TABLE = "OUTBOX_DONE_PLANS_NO_WAKE_UP";

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "969";

  /**
   * How long a dispatched entry is kept. It is short, so its end falls inside the test.
   */
  private static final Duration RETENTION = Duration.ofSeconds(2);

  /**
   * How long the dispatcher has to take no connection before the dispatch counts as over.
   * It is well below {@link #RETENTION}. So a wake-up at the end of the retention is not
   * hidden in this wait.
   */
  private static final Duration QUIET_FOR = Duration.ofMillis(500);

  /**
   * How long a test waits for something it expects to happen. It guards against a machine
   * which leaves the JVM without a turn. It does not measure speed.
   */
  private static final Duration UNTIL_IT_HAPPENED = Duration.ofSeconds(30);

  private static final String INSERT_ENTRY = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, \
      IDEMPOTENCY_KEY, DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT) \
      VALUES (?, '%s', '%s', ?, '%s', 'dummy', NULL, NULL, ?, '%s', ?, 0, ?)""";

  private static final String SELECT_STATUS = "SELECT STATUS FROM %s WHERE ID = ?";

  @Mock
  private MigrationProcessService<Object> processService;

  private final AtomicInteger connectionsTaken = new AtomicInteger();

  /**
   * The database of this test. Every connection the dispatcher takes is counted.
   */
  private final JdbcConnectionAccess countedConnections = () -> {
    connectionsTaken.incrementAndGet();
    return DriverManager.getConnection("jdbc:h2:mem:done-plans-no-wake-up;DB_CLOSE_DELAY=-1", "sa", "");
  };

  /**
   * The same database without the count. The test reads the table through it, and those
   * reads must not look like a wake-up.
   */
  private final JdbcConnectionAccess uncountedConnections = () -> DriverManager
      .getConnection("jdbc:h2:mem:done-plans-no-wake-up;DB_CLOSE_DELAY=-1", "sa", "");

  private static PhaseTwoOutboxProperties withAShortRetentionAndALongCap() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setRetention(RETENTION);
    properties.setPollInterval(Duration.ofHours(1));
    properties.setAttemptFrequency(Duration.ofMillis(200));
    // an hour which is over, and stays over for the length of this test
    final var now = LocalTime.now(ZoneId.systemDefault());
    properties.getHousekeeping().setStart(now.minusHours(3));
    properties.getHousekeeping().setEnd(now.minusHours(2));
    return properties;

  }

  private PhaseTwoRouter aRouterWhichDispatches() {

    when(processService.getWorkflowModuleId()).thenReturn(MODULE);
    when(processService.getBpmnProcessId()).thenReturn(PROCESS);
    when(processService.convertAggregateId(AGGREGATE)).thenReturn(AGGREGATE);
    Mockito
        .doAnswer(invocation -> null)
        .when(processService)
        .executePhaseTwo(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
    final var router = new PhaseTwoRouter();
    router.register(processService);
    return router;

  }

  private String anOpenEntry() throws SQLException {

    final var id = UUID.randomUUID().toString();
    final var now = Timestamp.from(Instant.now());
    try (var connection = uncountedConnections.acquire(); var statement = connection
        .prepareStatement(
            INSERT_ENTRY
                .formatted(TABLE, MODULE, PROCESS, AGGREGATE, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN))) {
      statement.setString(1, id);
      statement.setString(2, PhaseOperation.CORRELATE_MESSAGE.name());
      statement.setString(3, id);
      statement.setTimestamp(4, now);
      statement.setTimestamp(5, now);
      statement.executeUpdate();
    }
    return id;

  }

  private String statusOf(
      final String id) throws SQLException {

    try (var connection = uncountedConnections.acquire(); var statement = connection
        .prepareStatement(SELECT_STATUS.formatted(TABLE))) {
      statement.setString(1, id);
      try (var resultSet = statement.executeQuery()) {
        assertTrue(resultSet.next(), "the entry is gone");
        return resultSet.getString(1);
      }
    }

  }

  /**
   * Waits until the dispatcher took no connection for {@link #QUIET_FOR}. The dispatch which
   * marked the entry DONE still asks when to wake up next after the mark. That question has
   * to be over before the count starts.
   */
  private void awaitTheDispatchWentQuiet() throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED.toMillis();
    var takenSeen = connectionsTaken.get();
    var quietSince = System.currentTimeMillis();
    while ((System.currentTimeMillis() - quietSince) < QUIET_FOR.toMillis()) {
      assertTrue(System.currentTimeMillis() < deadline, "connections were taken over and over after the dispatch");
      Thread.sleep(20);
      final var takenNow = connectionsTaken.get();
      if (takenNow != takenSeen) {
        takenSeen = takenNow;
        quietSince = System.currentTimeMillis();
      }
    }

  }

  @Test
  @DisplayName("The end of the retention of a DONE entry wakes nobody")
  public void theEndOfTheRetentionWakesNobody() throws Exception {

    final var dispatcher = new JdbcPhaseTwoOutboxDispatcher(
        countedConnections, withAShortRetentionAndALongCap(), TABLE, new JdbcPhaseTwoPayloadStore(
            countedConnections, TABLE + JdbcPhaseTwoPayloadStore.TABLE_NAME_SUFFIX, JdbcPhaseTwoOutboxStore
                .entriesNamingTheirPayload(
                    TABLE)), this::aRouterWhichDispatches, () -> VanillaBpMetrics.NONE, "JdbcPhaseTwoOutbox");
    dispatcher.prepareSchema();
    final var id = anOpenEntry();

    try {
      dispatcher.start();
      final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED.toMillis();
      while (!JdbcPhaseTwoOutboxDispatcher.STATUS_DONE.equals(statusOf(id))) {
        assertTrue(System.currentTimeMillis() < deadline, "the entry was never dispatched");
        Thread.sleep(20);
      }
      // the dispatch took connections. That makes the zero below a measurement and not
      // just silence
      assertTrue(connectionsTaken.get() > 0);
      awaitTheDispatchWentQuiet();
      connectionsTaken.set(0);

      Thread.sleep(RETENTION.plusSeconds(1).toMillis());

      assertEquals(
          0,
          connectionsTaken.get(),
          "the retention of the DONE entry ended, and the poller woke up for it. The housekeeping "
              + "deletes such an entry in its night window, so a wake-up has nothing to do with it");
      assertEquals(JdbcPhaseTwoOutboxDispatcher.STATUS_DONE, statusOf(id), "only the housekeeping deletes it");
    } finally {
      dispatcher.stop();
    }

  }

}
