package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The table every JDBC-backed outbox of VanillaBP puts its payloads in: what is written
 * comes back byte for byte, what was dispatched is removed, and what a crash left behind
 * is removed by the age sweep.
 * <p>
 * The sweep is one statement which asks the entries itself, so every test about it needs
 * a table of entries beside the payloads. It is built here with the columns the outbox
 * store writes, and the entries are written by hand: what is under test is the question
 * the sweep asks, not the way an entry gets into the table.
 */
@ExtendWith(SuppressOutputExtension.class)
public class JdbcPhaseTwoPayloadStoreTest {

  private static final PhaseOperation OPERATION = PhaseOperation
      .extensionOperation("sample:NOTIFY")
      .idempotencyKey(call -> Optional.empty())
      .build();

  private static final String TABLE_NAME = "VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD";

  private static final String OUTBOX_TABLE_NAME = JdbcPhaseTwoOutboxStore.DEFAULT_TABLE_NAME;

  /**
   * The outbox table, reduced to what the sweep looks at. The real one carries a dozen
   * more columns, and none of them takes part in the question which payloads are still
   * named.
   */
  private static final String CREATE_ENTRIES = """
      CREATE TABLE IF NOT EXISTS %s (\
      ID VARCHAR(36) PRIMARY KEY, \
      ARGS VARCHAR(2048))"""
      .formatted(OUTBOX_TABLE_NAME);

  private static JdbcConnectionAccess h2(
      final String name) {

    return () -> DriverManager
        .getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  private static JdbcPhaseTwoPayloadStore storeOn(
      final String database) {

    return new JdbcPhaseTwoPayloadStore(
        h2(database), TABLE_NAME, JdbcPhaseTwoOutboxStore.entriesNamingTheirPayload(OUTBOX_TABLE_NAME));

  }

  /**
   * Builds the payload table and the table of entries beside it.
   *
   * @param database The database this test works on
   * @return The store to work with
   */
  private static JdbcPhaseTwoPayloadStore preparedStoreOn(
      final String database) {

    final var store = storeOn(database);
    store.createSchemaIfNotExists();
    execute(h2(database), CREATE_ENTRIES);
    return store;

  }

  /**
   * Writes an outbox entry naming a payload, the way the outbox store writes it: the
   * reference stands among the serialized arguments.
   *
   * @param database The database this test works on
   * @param call The call whose payload the entry names
   */
  private static void writeEntryNaming(
      final String database,
      final PhaseTwoCall call) {

    execute(
        h2(database),
        "INSERT INTO %s (ID, ARGS) VALUES ('%s', '%s')"
            .formatted(
                OUTBOX_TABLE_NAME,
                call.payloadReference(),
                PhaseTwoCall
                    .serializeArgs(Map.of(PhaseTwoCall.ARG_PAYLOAD_REFERENCE, call.payloadReference()))));

  }

  private static void execute(
      final JdbcConnectionAccess connections,
      final String statement) {

    Connection connection = null;
    try {
      connection = connections.acquire();
      try (var command = connection.createStatement()) {
        command.executeUpdate(statement);
      }
    } catch (final SQLException e) {
      throw new IllegalStateException("could not run '%s'".formatted(statement), e);
    } finally {
      if (connection != null) {
        try {
          connections.release(connection);
        } catch (final SQLException e) {
          throw new IllegalStateException("could not give the connection back", e);
        }
      }
    }

  }

  private static PhaseTwoCall callWith(
      final String content) {

    return PhaseTwoCall
        .of(
            OPERATION, "module", "Process", "42", null, Map.of(),
            content.getBytes(StandardCharsets.UTF_8));

  }

  @Test
  @DisplayName("What was written comes back byte for byte")
  public void aPayloadComesBackUnchanged() {

    final var store = preparedStoreOn("payload-roundtrip");

    final var call = callWith("the state at the sync point");
    store.write(call);

    assertArrayEquals(
        "the state at the sync point".getBytes(StandardCharsets.UTF_8),
        store.read(call.payloadReference()));

  }

  @Test
  @DisplayName("A reference nothing was written for reads as nothing")
  public void anUnknownReferenceReadsAsNothing() {

    final var store = preparedStoreOn("payload-unknown");

    assertNull(store.read("no-such-reference"));

  }

  @Test
  @DisplayName("A dispatched payload is removed, and removing it twice is no error")
  public void aDispatchedPayloadIsRemoved() {

    final var store = preparedStoreOn("payload-remove");

    final var call = callWith("gone after the dispatch");
    store.write(call);
    store.remove(call.payloadReference());

    assertNull(store.read(call.payloadReference()));
    assertDoesNotThrow(() -> store.remove(call.payloadReference()));

  }

  @Test
  @DisplayName("The age sweep removes what a crash between the two writes left behind")
  public void theAgeSweepRemovesOrphans() {

    final var store = preparedStoreOn("payload-sweep");

    final var orphan = callWith("written by a process which then died");
    store.write(orphan);

    // nothing is old enough yet, so the sweep of a moment in the past removes none
    assertEquals(0, store.removeOrphansOlderThan(Instant.now().minus(Duration.ofDays(1)), 100));
    assertArrayEquals(
        "written by a process which then died".getBytes(StandardCharsets.UTF_8),
        store.read(orphan.payloadReference()));

    assertEquals(1, store.removeOrphansOlderThan(Instant.now().plus(Duration.ofSeconds(1)), 100));
    assertNull(store.read(orphan.payloadReference()));

  }

  @Test
  @DisplayName("The age sweep leaves a payload an entry still names, however old it is")
  public void theAgeSweepLeavesWhatAnEntryNames() {

    final var store = preparedStoreOn("payload-sweep-named");

    final var named = callWith("the state an entry is still waiting to send");
    final var orphan = callWith("written by a process which then died");
    store.write(named);
    store.write(orphan);
    writeEntryNaming("payload-sweep-named", named);

    final var removed = store.removeOrphansOlderThan(Instant.now().plus(Duration.ofSeconds(1)), 100);

    assertEquals(1, removed, "only the payload no entry names may go");
    assertArrayEquals(
        "the state an entry is still waiting to send".getBytes(StandardCharsets.UTF_8),
        store.read(named.payloadReference()));
    assertNull(store.read(orphan.payloadReference()));

  }

  @Test
  @DisplayName("The sweep removes at most as many payloads as it was allowed to")
  public void theSweepRemovesAtMostWhatItWasAllowedTo() {

    final var store = preparedStoreOn("payload-sweep-bounded");

    final var orphans = new PhaseTwoCall[5];
    for (var index = 0; index < orphans.length; index++) {
      orphans[index] = callWith("orphan number %d".formatted(index));
      store.write(orphans[index]);
    }

    final var expired = Instant.now().plus(Duration.ofSeconds(1));
    assertEquals(2, store.removeOrphansOlderThan(expired, 2), "the ceiling was not kept");
    assertEquals(2, store.removeOrphansOlderThan(expired, 2), "the next run takes the next two");
    assertEquals(1, store.removeOrphansOlderThan(expired, 2), "a run which comes back short is the last one");
    assertEquals(0, store.removeOrphansOlderThan(expired, 2));

  }

  @Test
  @DisplayName("A ceiling of nothing removes nothing and asks the database nothing")
  public void aCeilingOfNothingRemovesNothing() {

    final var store = preparedStoreOn("payload-sweep-no-room");
    final var orphan = callWith("written by a process which then died");
    store.write(orphan);

    assertEquals(0, store.removeOrphansOlderThan(Instant.now().plus(Duration.ofSeconds(1)), 0));
    assertNotNull(store.read(orphan.payloadReference()));

  }

  @Test
  @DisplayName("The sweep is one statement, so it never holds two connections at once")
  public void theSweepHoldsOneConnectionAtATime() {

    final var connections = lendingOneConnectionAtATime("payload-sweep-one-connection");
    final var store = new JdbcPhaseTwoPayloadStore(
        connections, TABLE_NAME, JdbcPhaseTwoOutboxStore.entriesNamingTheirPayload(OUTBOX_TABLE_NAME));
    store.createSchemaIfNotExists();
    execute(connections, CREATE_ENTRIES);
    final var orphan = callWith("written by a process which then died");
    store.write(orphan);

    // the entries lie in a table of their own, and the sweep reads it inside its own
    // statement rather than on a second connection. A store borrowing one per step would
    // wait for a connection it is holding itself, which on a pool of one never comes back
    final var removed = store.removeOrphansOlderThan(Instant.now().plus(Duration.ofSeconds(1)), 100);

    assertEquals(1, removed, "the sweep did not get through on one connection");
    assertNull(store.read(orphan.payloadReference()));

  }

  /**
   * The same database, handing out one connection at a time and refusing a second one
   * rather than making its caller wait. A pool makes the second caller wait, and a test
   * doing that would hang instead of failing; refusing turns the same mistake into a
   * message.
   *
   * @param name The database this test works on
   * @return Connections to it, one at a time
   */
  private static JdbcConnectionAccess lendingOneConnectionAtATime(
      final String name) {

    final var lentOut = new AtomicBoolean();
    return new JdbcConnectionAccess() {

      @Override
      public Connection acquire() throws SQLException {

        if (!lentOut.compareAndSet(false, true)) {
          throw new SQLException("somebody asked for a second connection while the first one was out");
        }
        return h2(name).acquire();

      }

      @Override
      public void release(
          final Connection connection) throws SQLException {

        try {
          connection.close();
        } finally {
          lentOut.set(false);
        }

      }

    };

  }

  @Test
  @DisplayName("A missing table ends the startup naming the table, the property and the artifact")
  public void aMissingTableIsReportedAtStartup() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> storeOn("payload-missing").validateSchemaExists());

    assertTrue(failure.getMessage().contains(TABLE_NAME), failure.getMessage());
    assertTrue(failure.getMessage().contains("vanillabp.outbox.create-schema"), failure.getMessage());
    assertTrue(failure.getMessage().contains("io.vanillabp:vanillabp-schema"), failure.getMessage());
    assertTrue(failure.getMessage().contains("vanillabp/schema/changelog.xml"), failure.getMessage());

  }

  @Test
  @DisplayName("With the table in place the check passes - and the runtime's own DDL satisfies it")
  public void anExistingTablePasses() {

    final var store = storeOn("payload-created");
    store.createSchemaIfNotExists();

    assertDoesNotThrow(store::validateSchemaExists);
    // a second start finds the table and leaves it alone
    assertDoesNotThrow(store::createSchemaIfNotExists);

  }

  @Test
  @DisplayName("The runtime creates the index the sweep deletes along")
  public void theRuntimeCreatesItsIndex() throws Exception {

    final var store = storeOn("payload-index");
    store.createSchemaIfNotExists();

    try (var connection = h2("payload-index").acquire(); var resultSet = connection.getMetaData().getIndexInfo(null,
        null, TABLE_NAME, false, true)) {
      var found = false;
      while (resultSet.next()) {
        final var name = resultSet.getString("INDEX_NAME");
        if ((name != null) && name.equalsIgnoreCase(TABLE_NAME
            + "_AGE")) {
          assertEquals("CREATED_AT", resultSet.getString("COLUMN_NAME"));
          found = true;
        }
      }
      assertTrue(found, "the sweep deletes by CREATED_AT and needs the index over it");
    }

  }

  /**
   * A payload written at a moment of the test's choosing, which is how a test about the
   * age sweep gets a row older than the retention without waiting for it.
   *
   * @param database The database this test works on
   * @param reference The payload to age
   * @param writtenAt The moment it is to have been written at
   */
  private static void age(
      final String database,
      final String reference,
      final Instant writtenAt) {

    execute(
        h2(database),
        "UPDATE %s SET CREATED_AT = '%s' WHERE REFERENCE = '%s'"
            .formatted(TABLE_NAME, Timestamp.from(writtenAt), reference));

  }

  @Test
  @DisplayName("A payload younger than the threshold stays, whatever nothing names it")
  public void aYoungPayloadStays() {

    final var store = preparedStoreOn("payload-young");
    final var young = callWith("written a moment ago");
    store.write(young);
    age("payload-young", young.payloadReference(), Instant.now().plus(Duration.ofHours(1)));

    assertEquals(0, store.removeOrphansOlderThan(Instant.now(), 100));
    assertNotNull(store.read(young.payloadReference()));

  }

}
