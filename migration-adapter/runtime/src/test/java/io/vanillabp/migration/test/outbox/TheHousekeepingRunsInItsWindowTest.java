package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The outbox removes what it does not need any more inside a window and at no other
 * time. That is the whole point of the window: an application used to pay for the
 * housekeeping at the end of every poll, and now it pays for it once a night.
 * <p>
 * The tests work with a window of their own rather than with the default hour of the
 * night, because a test which waited for four in the morning would run once a day.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheHousekeepingRunsInItsWindowTest {

  private static final String OUTBOX_TABLE = JdbcPhaseTwoOutboxStore.DEFAULT_TABLE_NAME;

  private static final String PAYLOAD_TABLE = JdbcPhaseTwoPayloadStore.DEFAULT_TABLE_NAME;

  private static final PhaseOperation OPERATION = PhaseOperation
      .extensionOperation("sample:NOTIFY")
      .idempotencyKey(call -> Optional.empty())
      .build();

  /**
   * How long a test waits for a window which is open to do its work. It is a generous
   * bound rather than a promise about the speed: a run which needs it all is a run on a
   * loaded machine, and what the test would report either way is that the housekeeping
   * never ran.
   */
  private static final Duration UNTIL_THE_WINDOW_RAN = Duration.ofSeconds(20);

  /**
   * How long a test watches a window which is SHUT before it believes that nothing
   * happens. It is short, because what it proves is the absence of work and every second
   * of it is a second of the build.
   */
  private static final Duration WATCHING_A_SHUT_WINDOW = Duration.ofSeconds(2);

  private static JdbcConnectionAccess h2(
      final String name) {

    return () -> DriverManager
        .getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  /**
   * The outbox configuration with a window of this test's making.
   *
   * @param open Whether the window is open while the test runs
   * @return The configuration to build the dispatcher with
   */
  private static PhaseTwoOutboxProperties withAWindowWhichIs(
      final boolean open) {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setPollInterval(Duration.ofMillis(200));
    properties.setRetention(Duration.ofSeconds(1));
    final var now = LocalTime.now(ZoneId.systemDefault());
    if (open) {
      properties.getHousekeeping().setStart(LocalTime.MIN);
      properties.getHousekeeping().setEnd(LocalTime.MAX);
    } else {
      // an hour which is over, and which stays over for the length of this test
      properties.getHousekeeping().setStart(now.minusHours(3));
      properties.getHousekeeping().setEnd(now.minusHours(2));
    }
    return properties;

  }

  private static PhaseTwoCall callWith(
      final String content) {

    return PhaseTwoCall
        .of(
            OPERATION, "module", "Process", "42", null, Map.of(),
            content.getBytes(StandardCharsets.UTF_8));

  }

  /**
   * Writes a dispatched entry whose retention ran out long ago, the way the store writes
   * one. It is written by hand because what is under test is the removal, not the
   * dispatch which leads to it.
   *
   * @param connections The database of this test
   * @param call The call the entry belongs to
   * @return The id of the entry
   */
  private static String anEntryDispatchedLongAgo(
      final JdbcConnectionAccess connections,
      final PhaseTwoCall call) throws SQLException {

    final var id = UUID
        .randomUUID()
        .toString();
    final var longAgo = Timestamp.from(Instant.now().minus(Duration.ofDays(30)));
    Connection connection = null;
    try {
      connection = connections.acquire();
      try (var statement = connection
          .prepareStatement("""
              INSERT INTO %s (ID, WORKFLOW_MODULE_ID, BPMN_PROCESS_ID, OPERATION, ARGS, DEDUP_KEY, \
              STATUS, CREATED_AT, ATTEMPTS, NEXT_ATTEMPT_AT, DONE_AT) \
              VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)""".formatted(OUTBOX_TABLE))) {
        statement.setString(1, id);
        statement.setString(2, call.workflowModuleId());
        statement.setString(3, call.bpmnProcessId());
        statement.setString(4, call.operation());
        statement.setString(5, PhaseTwoCall.serializeArgs(call.args()));
        statement.setString(6, id);
        statement.setString(7, JdbcPhaseTwoOutboxDispatcher.STATUS_DONE);
        statement.setTimestamp(8, longAgo);
        statement.setTimestamp(9, longAgo);
        statement.setTimestamp(10, longAgo);
        statement.executeUpdate();
      }
    } finally {
      connections.release(connection);
    }
    return id;

  }

  /**
   * Writes a payload with a moment of the test's choosing, which is how an orphan older
   * than the retention comes about without waiting for it.
   *
   * @param store The payload store
   * @param connections The database of this test
   * @param call The call whose payload is written
   */
  private static void aPayloadWrittenLongAgo(
      final JdbcPhaseTwoPayloadStore store,
      final JdbcConnectionAccess connections,
      final PhaseTwoCall call) throws SQLException {

    store.write(call);
    Connection connection = null;
    try {
      connection = connections.acquire();
      try (var statement = connection
          .prepareStatement("UPDATE %s SET CREATED_AT = ? WHERE REFERENCE = ?".formatted(PAYLOAD_TABLE))) {
        statement.setTimestamp(1, Timestamp.from(Instant.now().minus(Duration.ofDays(30))));
        statement.setString(2, call.payloadReference());
        statement.executeUpdate();
      }
    } finally {
      connections.release(connection);
    }

  }

  private static long countEntries(
      final JdbcConnectionAccess connections) throws SQLException {

    Connection connection = null;
    try {
      connection = connections.acquire();
      try (var statement = connection.prepareStatement(
          "SELECT COUNT(*) FROM %s".formatted(OUTBOX_TABLE)); var resultSet = statement.executeQuery()) {
        resultSet.next();
        return resultSet.getLong(1);
      }
    } finally {
      connections.release(connection);
    }

  }

  /**
   * What the housekeeping published about its last window, kept by a metrics double.
   */
  private static final class WhatTheWindowSaid implements VanillaBpMetrics {

    private final AtomicReference<Supplier<OptionalLong>> remaining = new AtomicReference<>();

    private final AtomicReference<Supplier<OptionalLong>> removed = new AtomicReference<>();

    private final AtomicReference<Supplier<Optional<Duration>>> used = new AtomicReference<>();

    @Override
    public void registerHousekeeping(
        final String store,
        final Supplier<OptionalLong> remaining,
        final Supplier<OptionalLong> removed,
        final Supplier<Optional<Duration>> windowUsed) {

      this.remaining.set(remaining);
      this.removed.set(removed);
      this.used.set(windowUsed);

    }

  }

  @Test
  @DisplayName("Outside its window the housekeeping removes nothing, however old the rows are")
  public void aShutWindowRemovesNothing() throws Exception {

    final var connections = h2("housekeeping-window-shut");
    final var payloadStore = new JdbcPhaseTwoPayloadStore(
        connections, PAYLOAD_TABLE, JdbcPhaseTwoOutboxStore.entriesNamingTheirPayload(OUTBOX_TABLE));
    final var dispatcher = new JdbcPhaseTwoOutboxDispatcher(
        connections, withAWindowWhichIs(
            false), OUTBOX_TABLE, payloadStore, () -> null, () -> VanillaBpMetrics.NONE, "JdbcPhaseTwoOutbox");
    dispatcher.prepareSchema();

    final var dispatched = callWith("the state a dispatch already carried");
    final var orphan = callWith("written by a transaction which never committed");
    anEntryDispatchedLongAgo(connections, dispatched);
    aPayloadWrittenLongAgo(payloadStore, connections, orphan);

    try {
      dispatcher.start();
      Thread.sleep(WATCHING_A_SHUT_WINDOW.toMillis());
    } finally {
      dispatcher.stop();
    }

    assertEquals(1, countEntries(connections), "an entry whose retention ran out waits for the window");
    assertNotNull(payloadStore.read(orphan.payloadReference()), "and so does a payload nothing names");

  }

  @Test
  @DisplayName("Inside its window the housekeeping removes the entries and the payloads, and says what it did")
  public void anOpenWindowRemovesBothAndPublishesWhatItDid() throws Exception {

    final var connections = h2("housekeeping-window-open");
    final var payloadStore = new JdbcPhaseTwoPayloadStore(
        connections, PAYLOAD_TABLE, JdbcPhaseTwoOutboxStore.entriesNamingTheirPayload(OUTBOX_TABLE));
    final var said = new WhatTheWindowSaid();
    final var dispatcher = new JdbcPhaseTwoOutboxDispatcher(
        connections, withAWindowWhichIs(
            true), OUTBOX_TABLE, payloadStore, () -> null, () -> said, "JdbcPhaseTwoOutbox");
    dispatcher.prepareSchema();

    final var dispatched = callWith("the state a dispatch already carried");
    final var orphan = callWith("written by a transaction which never committed");
    anEntryDispatchedLongAgo(connections, dispatched);
    aPayloadWrittenLongAgo(payloadStore, connections, orphan);

    try {
      dispatcher.start();
      final var deadline = System.currentTimeMillis() + UNTIL_THE_WINDOW_RAN.toMillis();
      while (payloadStore.read(orphan.payloadReference()) != null) {
        assertTrue(System.currentTimeMillis() < deadline, "the housekeeping did not remove the orphaned payload");
        Thread.sleep(50);
      }
    } finally {
      dispatcher.stop();
    }

    assertEquals(0, countEntries(connections), "a dispatched entry goes when its retention ran out");
    assertNull(payloadStore.read(orphan.payloadReference()));
    assertNotNull(said.remaining.get(), "the three numbers of a window are published when the store starts");
    assertNotNull(said.removed.get());
    assertNotNull(said.used.get());

  }

  @Test
  @DisplayName("Only one node of a cluster house-keeps a store, so nobody measures another node's work")
  public void onlyOneNodeHouseKeepsAStore() throws Exception {

    final var connections = h2("housekeeping-two-nodes");
    final var payloadStore = new JdbcPhaseTwoPayloadStore(
        connections, PAYLOAD_TABLE, JdbcPhaseTwoOutboxStore.entriesNamingTheirPayload(OUTBOX_TABLE));
    final var firstNode = new JdbcPhaseTwoOutboxDispatcher(
        connections, withAWindowWhichIs(
            true), OUTBOX_TABLE, payloadStore, () -> null, () -> VanillaBpMetrics.NONE, "JdbcPhaseTwoOutbox");
    final var secondNode = new JdbcPhaseTwoOutboxDispatcher(
        connections, withAWindowWhichIs(
            true), OUTBOX_TABLE, payloadStore, () -> null, () -> VanillaBpMetrics.NONE, "JdbcPhaseTwoOutbox");
    firstNode.prepareSchema();

    // the claim is what a node takes before it works, and the second node is answered no
    assertTrue(firstNode.claimHousekeepingUntil("first-node", Instant.now().plus(Duration.ofHours(1))));
    assertFalse(secondNode.claimHousekeepingUntil("second-node", Instant.now().plus(Duration.ofHours(1))));

    firstNode.releaseHousekeeping("first-node");

    assertTrue(
        secondNode.claimHousekeepingUntil("second-node", Instant.now().plus(Duration.ofHours(1))),
        "a claim given back leaves the store to whoever asks next");

  }

}
