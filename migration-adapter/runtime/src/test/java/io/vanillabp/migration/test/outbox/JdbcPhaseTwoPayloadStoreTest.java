package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoPayloadStore;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoPayloadStore;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The table every JDBC-backed outbox of VanillaBP puts its payloads in: what is written
 * comes back byte for byte, what was dispatched is removed, and what a crash left behind
 * is removed by the age sweep.
 */
@ExtendWith(SuppressOutputExtension.class)
public class JdbcPhaseTwoPayloadStoreTest {

  private static final PhaseOperation OPERATION = PhaseOperation
      .extensionOperation("sample:NOTIFY")
      .idempotencyKey(call -> Optional.empty())
      .build();

  private static final String TABLE_NAME = "VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD";

  /**
   * The answer of an outbox whose entries name none of the payloads asked about - the
   * store of a test which is about the sweep itself.
   */
  private static final PhaseTwoPayloadStore.EntriesNamingPayloads NO_ENTRY_NAMES_ANY = references -> Set.of();

  private static JdbcConnectionAccess h2(
      final String name) {

    return () -> DriverManager
        .getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  private static JdbcPhaseTwoPayloadStore storeOn(
      final String database) {

    return new JdbcPhaseTwoPayloadStore(h2(database), TABLE_NAME);

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

    final var store = storeOn("payload-roundtrip");
    store.createSchemaIfNotExists();

    final var call = callWith("the state at the sync point");
    store.write(call);

    assertArrayEquals(
        "the state at the sync point".getBytes(StandardCharsets.UTF_8),
        store.read(call.payloadReference()));

  }

  @Test
  @DisplayName("A reference nothing was written for reads as nothing")
  public void anUnknownReferenceReadsAsNothing() {

    final var store = storeOn("payload-unknown");
    store.createSchemaIfNotExists();

    assertNull(store.read("no-such-reference"));

  }

  @Test
  @DisplayName("A dispatched payload is removed, and removing it twice is no error")
  public void aDispatchedPayloadIsRemoved() {

    final var store = storeOn("payload-remove");
    store.createSchemaIfNotExists();

    final var call = callWith("gone after the dispatch");
    store.write(call);
    store.remove(call.payloadReference());

    assertNull(store.read(call.payloadReference()));
    assertDoesNotThrow(() -> store.remove(call.payloadReference()));

  }

  @Test
  @DisplayName("The age sweep removes what a crash between the two writes left behind")
  public void theAgeSweepRemovesOrphans() {

    final var store = storeOn("payload-sweep");
    store.createSchemaIfNotExists();

    final var orphan = callWith("written by a process which then died");
    store.write(orphan);

    // nothing is old enough yet, so the sweep of a moment in the past removes none
    assertEquals(0, store.removeOrphansOlderThan(Instant.now().minus(Duration.ofDays(1)), NO_ENTRY_NAMES_ANY));
    assertArrayEquals(
        "written by a process which then died".getBytes(StandardCharsets.UTF_8),
        store.read(orphan.payloadReference()));

    assertEquals(1, store.removeOrphansOlderThan(Instant.now().plus(Duration.ofSeconds(1)), NO_ENTRY_NAMES_ANY));
    assertNull(store.read(orphan.payloadReference()));

  }

  @Test
  @DisplayName("The age sweep leaves a payload an entry still names, however old it is")
  public void theAgeSweepLeavesWhatAnEntryNames() {

    final var store = storeOn("payload-sweep-named");
    store.createSchemaIfNotExists();

    final var named = callWith("the state an entry is still waiting to send");
    final var orphan = callWith("written by a process which then died");
    store.write(named);
    store.write(orphan);

    final var removed = store
        .removeOrphansOlderThan(
            Instant.now().plus(Duration.ofSeconds(1)), references -> Set.of(named.payloadReference()));

    assertEquals(1, removed, "only the payload no entry names may go");
    assertArrayEquals(
        "the state an entry is still waiting to send".getBytes(StandardCharsets.UTF_8),
        store.read(named.payloadReference()));
    assertNull(store.read(orphan.payloadReference()));

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

}
