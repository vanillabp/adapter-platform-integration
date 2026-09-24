package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoPermanentFailure;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * What happens when an attempt ended and the database could not be asked to write down how.
 * <p>
 * Such a write is not the same as a write which matched no row. A row which does not match
 * says that the entry changed hands, and the node holding it now writes what became of the
 * operation. A database which cannot be asked says nothing at all: the entry keeps its
 * status, its due time and its lease, this node still holds it, and the next poll reads it
 * again once that lease runs out. So the entry has to keep its payload, and it must not be
 * counted or reported as blocked either - an operator would go and repair a row which says
 * OPEN.
 * <p>
 * The failing write is a real one and needs no double: the mark writes the entry's own id
 * into <code>DEDUP_KEY</code>, which the table holds unique, so a second row already
 * carrying that id there is enough to make every mark of this entry fail.
 */
@ExtendWith(SuppressOutputExtension.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@SuppressBackgroundOutput
public class AMarkWhichDidNotGetThroughLeavesTheEntryTest {

  /**
   * How long a claim lasts here. Long enough that exactly one attempt happens while this
   * test looks: the entry stays claimed after the write which failed, and the poll which
   * reads it again would dispatch the operation a second time.
   */
  private static final Duration LEASE = Duration.ofSeconds(30);

  /**
   * How long a test waits for something it expects to happen. It is a guard against a
   * machine which leaves the JVM without a turn, not a measurement of speed.
   */
  private static final Duration UNTIL_IT_HAPPENED = Duration.ofSeconds(30);

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String AGGREGATE = "42";

  private static final String INSERT_ENTRY = """
      INSERT INTO %s \
      (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, AGGREGATE_ID, ADAPTER_ID, ARGS, \
      IDEMPOTENCY_KEY, DEDUP_KEY, STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT, DONE_AT) \
      VALUES (?, '%s', '%s', ?, '%s', 'dummy', ?, NULL, ?, ?, ?, 0, ?, ?)""";

  private static final String SELECT_ENTRY = """
      SELECT STATUS, ATTEMPTS FROM %s WHERE ID = ?""";

  @Mock
  private MigrationProcessService<Object> theNodeDispatching;

  private final AtomicInteger callsIntoTheAdapter = new AtomicInteger();

  private final AtomicInteger entriesCountedAsBlocked = new AtomicInteger();

  /**
   * What the dispatcher wrote into the log, which is where an operator reads what became of
   * an attempt.
   */
  private final ListAppender<ILoggingEvent> whatWasLogged = new ListAppender<>();

  private Logger dispatcherLog;

  @BeforeEach
  public void watchTheLog() {

    whatWasLogged.start();
    dispatcherLog = (Logger) LoggerFactory.getLogger(JdbcPhaseTwoOutboxDispatcher.class);
    dispatcherLog.addAppender(whatWasLogged);

  }

  @AfterEach
  public void stopWatchingTheLog() {

    dispatcherLog.detachAppender(whatWasLogged);
    whatWasLogged.stop();

  }

  private boolean somethingWasLoggedAbout(
      final String entry,
      final String phrase) {

    return whatWasLogged.list
        .stream()
        .map(ILoggingEvent::getFormattedMessage)
        .anyMatch(message -> message.contains(entry) && message.contains(phrase));

  }

  /**
   * A database per test, because every test of this class writes into the outbox table and
   * reads what is in it afterwards.
   */
  private static JdbcConnectionAccess databaseNamed(
      final String name) {

    return () -> DriverManager.getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  /**
   * What this test reads of the meters: the entries the dispatcher gave up on.
   */
  private final VanillaBpMetrics metrics = new VanillaBpMetrics() {

    @Override
    public void outboxEntryBlocked(
        final String storeName,
        final String operation,
        final boolean permanent) {

      entriesCountedAsBlocked.incrementAndGet();

    }

  };

  /**
   * The node dispatching the entry: its attempt ends the way the test asked for.
   *
   * @param endsWith What the dispatch throws, or <code>null</code> where it succeeds
   * @return The router of that node
   */
  private PhaseTwoRouter aRouterWhoseDispatch(
      final RuntimeException endsWith) {

    when(theNodeDispatching.getWorkflowModuleId()).thenReturn(MODULE);
    when(theNodeDispatching.getBpmnProcessId()).thenReturn(PROCESS);
    when(theNodeDispatching.convertAggregateId(AGGREGATE)).thenReturn(AGGREGATE);
    Mockito
        .doAnswer(invocation -> {
          callsIntoTheAdapter.incrementAndGet();
          if (endsWith != null) {
            throw endsWith;
          }
          return null;
        })
        .when(theNodeDispatching)
        .executePhaseTwo(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
    final var router = new PhaseTwoRouter();
    router.register(theNodeDispatching);
    return router;

  }

  /**
   * The payload store of a test, counting what the dispatcher asked it to remove. A removal
   * is what a mark which got through leads to, so counting them is how this test reads
   * whether the dispatcher believed its failed mark.
   */
  private static class PayloadStoreCountingRemovals extends JdbcPhaseTwoPayloadStore {

    private final AtomicInteger removals = new AtomicInteger();

    private PayloadStoreCountingRemovals(
        final JdbcConnectionAccess connectionAccess,
        final String tableName) {

      super(connectionAccess, tableName);

    }

    @Override
    public void remove(
        final String reference) {

      removals.incrementAndGet();
      super.remove(reference);

    }

    /**
     * @return How many payloads the dispatcher asked this store to remove
     */
    private int removals() {

      return removals.get();

    }

  }

  private JdbcPhaseTwoOutboxDispatcher dispatcherOf(
      final JdbcConnectionAccess connections,
      final JdbcPhaseTwoPayloadStore payloadStore,
      final PhaseTwoRouter router,
      final String table) {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setAttemptFrequency(LEASE);
    return new JdbcPhaseTwoOutboxDispatcher(
        connections, properties, table, payloadStore, () -> router, () -> metrics, "JdbcPhaseTwoOutbox");

  }

  /**
   * Writes the entry to be dispatched and, beside it, a dispatched entry which already
   * carries that entry's id as its <code>DEDUP_KEY</code>. Marking the first one therefore
   * cannot be written, whichever mark it is.
   *
   * @param connections The database of this test
   * @param table The outbox table of this test
   * @param call The call the entry stands for - its arguments name its payload
   * @return The id of the entry to be dispatched
   */
  private String anEntryWhoseMarkCannotBeWritten(
      final JdbcConnectionAccess connections,
      final String table,
      final PhaseTwoCall call) throws SQLException {

    final var id = UUID.randomUUID().toString();
    final var now = Instant.now();
    writeEntry(connections, table, id, PhaseTwoCall.serializeArgs(call.args()), "the-key-of-"
        + id, JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN, now, null);
    writeEntry(
        connections, table, UUID.randomUUID().toString(), null, id, JdbcPhaseTwoOutboxDispatcher.STATUS_DONE, now,
        now);
    return id;

  }

  private void writeEntry(
      final JdbcConnectionAccess connections,
      final String table,
      final String id,
      final String args,
      final String dedupKey,
      final String status,
      final Instant now,
      final Instant doneAt) throws SQLException {

    try (var connection = connections.acquire(); var statement = connection
        .prepareStatement(INSERT_ENTRY.formatted(table, MODULE, PROCESS, AGGREGATE))) {
      statement.setString(1, id);
      statement.setString(2, PhaseOperation.START_WORKFLOW.name());
      statement.setString(3, args);
      statement.setString(4, dedupKey);
      statement.setString(5, status);
      statement.setTimestamp(6, Timestamp.from(now));
      statement.setTimestamp(7, Timestamp.from(now));
      statement.setTimestamp(8, doneAt == null ? null : Timestamp.from(doneAt));
      statement.executeUpdate();
    }

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
      final Callable<Boolean> itHappened) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_IT_HAPPENED.toMillis();
    while (!itHappened.call()) {
      assertTrue(System.currentTimeMillis() < deadline, whatDidNotHappen);
      Thread.sleep(50);
    }

  }

  private static PhaseTwoCall callWith(
      final String content) {

    return PhaseTwoCall
        .of(
            PhaseOperation.START_WORKFLOW, MODULE, PROCESS, AGGREGATE, "dummy", Map.of(),
            content.getBytes(StandardCharsets.UTF_8));

  }

  @Test
  @DisplayName("An entry whose mark did not get through keeps its payload and waits for its next attempt")
  public void aMarkWhichDidNotGetThroughKeepsThePayload() throws Exception {

    final var table = "OUTBOX_FAILED_MARK_DONE";
    final var connections = databaseNamed("a-mark-which-did-not-get-through");
    final var payloadStore = new PayloadStoreCountingRemovals(
        connections, table + JdbcPhaseTwoPayloadStore.TABLE_NAME_SUFFIX);
    final var dispatcher = dispatcherOf(connections, payloadStore, aRouterWhoseDispatch(null), table);
    dispatcher.prepareSchema();
    final var call = callWith("the state the next attempt has to carry");
    payloadStore.write(call);
    final var entry = anEntryWhoseMarkCannotBeWritten(connections, table, call);

    try {
      // the two ends the attempt can have: it says that nothing was written, or it removes
      // the payload of an entry it believes to be dispatched. Waiting for either of them is
      // what makes this test say which one happened instead of running into its deadline
      dispatcher.start();
      waitUntil(
          "the attempt whose mark could not be written never ended",
          () -> (payloadStore.removals() > 0) || somethingWasLoggedAbout(entry,
              "stays as it is and is dispatched again"));
    } finally {
      dispatcher.stop();
    }

    assertEquals(1, callsIntoTheAdapter.get(), "the operation reached the adapter more than once");
    assertEquals(
        0,
        payloadStore.removals(),
        "the mark did not get through, so the entry is still open - and its payload was removed");
    assertArrayEquals(
        "the state the next attempt has to carry".getBytes(StandardCharsets.UTF_8),
        payloadStore.read(call.payloadReference()),
        "the entry is dispatched again, so the bytes it carries have to be there");
    assertEquals(
        new Entry(JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN, 0),
        entryOf(connections, table, entry),
        "nothing was written, so the entry says what it said before the attempt");

  }

  @Test
  @DisplayName("An entry whose blocking did not get through is not reported as blocked")
  public void anEntryWhoseBlockingDidNotGetThroughIsNotReportedAsBlocked() throws Exception {

    final var table = "OUTBOX_FAILED_MARK_BLOCKED";
    final var connections = databaseNamed("a-blocking-which-did-not-get-through");
    final var payloadStore = new JdbcPhaseTwoPayloadStore(
        connections, table + JdbcPhaseTwoPayloadStore.TABLE_NAME_SUFFIX);
    // the failure which blocks an entry with one attempt, so the blocking write is the
    // first thing the dispatcher tries after the attempt
    final var permanent = new PhaseTwoPermanentFailure("the adapter says repeating cannot help", null);
    final var dispatcher = dispatcherOf(connections, payloadStore, aRouterWhoseDispatch(permanent), table);
    dispatcher.prepareSchema();
    final var call = callWith("the state the next attempt has to carry");
    payloadStore.write(call);
    final var entry = anEntryWhoseMarkCannotBeWritten(connections, table, call);

    try {
      // either the attempt says that nothing was written, or it counts a blocked entry the
      // table knows nothing about - the test waits for whichever comes and names it
      dispatcher.start();
      waitUntil(
          "the attempt whose blocking could not be written never ended",
          () -> (entriesCountedAsBlocked.get() > 0) || somethingWasLoggedAbout(entry,
              "stays as it is and is dispatched again"));
    } finally {
      dispatcher.stop();
    }

    assertEquals(
        new Entry(JdbcPhaseTwoOutboxDispatcher.STATUS_OPEN, 0),
        entryOf(connections, table, entry),
        "nothing was written, so the entry is still waiting for an attempt");
    assertEquals(
        0,
        entriesCountedAsBlocked.get(),
        "an entry which is not blocked was counted as one, so the meter names a repair nobody owes");
    assertTrue(
        !somethingWasLoggedAbout(entry, "has to be cleaned up manually"),
        "an entry which says OPEN was reported as blocked, and somebody would go and repair it");

  }

}
