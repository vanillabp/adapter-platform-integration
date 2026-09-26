package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcConnectionAccess;
import io.vanillabp.integration.adapter.migration.outbox.JdbcHousekeepingLease;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which node house-keeps a store tonight. Two nodes doing it at the same time would each
 * measure the other's work, and the batch size they arrive at would be nonsense - so the
 * claim is what keeps the measurement honest, and these tests hold what it promises.
 */
@ExtendWith(SuppressOutputExtension.class)
public class JdbcHousekeepingLeaseTest {

  private static final String STORE = "JdbcPhaseTwoOutbox@VANILLABP_PHASE_TWO_OUTBOX";

  private static final String TABLE = PhaseTwoOutboxProperties.JdbcOutboxProperties.DEFAULT_HOUSEKEEPING_TABLE;

  private static JdbcConnectionAccess h2(
      final String name) {

    return () -> DriverManager
        .getConnection("jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(name), "sa", "");

  }

  private static JdbcHousekeepingLease leaseOn(
      final String database) {

    final var lease = new JdbcHousekeepingLease(h2(database), TABLE);
    lease.createSchemaIfNotExists();
    return lease;

  }

  private static Instant inAnHour() {

    return Instant
        .now()
        .plus(Duration.ofHours(1));

  }

  @Test
  @DisplayName("The first node to ask holds the store, and the second is answered no")
  public void onlyOneNodeHoldsTheStore() {

    final var lease = leaseOn("housekeeping-one-node");

    assertTrue(lease.claimUntil(STORE, "first-node", inAnHour()));
    assertFalse(lease.claimUntil(STORE, "second-node", inAnHour()), "two nodes would measure each other's work");

  }

  @Test
  @DisplayName("A store nobody claimed before is claimed with the row which is written for it")
  public void theFirstClaimWritesTheRow() {

    final var lease = leaseOn("housekeeping-first-row");

    // the row of a store is written by whoever house-keeps it first, and two nodes doing
    // that in the same moment are sorted out by its primary key
    assertTrue(lease.claimUntil("a-store-nobody-touched", "first-node", inAnHour()));
    assertFalse(lease.claimUntil("a-store-nobody-touched", "second-node", inAnHour()));

  }

  @Test
  @DisplayName("A released claim is free again, so a long window does not hold a store to its end")
  public void aReleasedClaimIsFree() {

    final var lease = leaseOn("housekeeping-release");
    lease.claimUntil(STORE, "first-node", inAnHour());

    lease.release(STORE, "first-node");

    assertTrue(lease.claimUntil(STORE, "second-node", inAnHour()));

  }

  @Test
  @DisplayName("A node which does not hold the store releases nothing")
  public void anotherNodeReleasesNothing() {

    final var lease = leaseOn("housekeeping-release-foreign");
    lease.claimUntil(STORE, "first-node", inAnHour());

    lease.release(STORE, "second-node");

    assertFalse(lease.claimUntil(STORE, "third-node", inAnHour()), "the claim of the first node still stands");

  }

  @Test
  @DisplayName("A claim which ran out is taken over, which is how a node that died frees the next night")
  public void anExpiredClaimIsTakenOver() {

    final var lease = leaseOn("housekeeping-expired");
    lease.claimUntil(STORE, "the-node-which-died", Instant.now().minus(Duration.ofMinutes(1)));

    assertTrue(lease.claimUntil(STORE, "the-node-which-is-alive", inAnHour()));

  }

  @Test
  @DisplayName("Two stores of one database are claimed one each")
  public void twoStoresAreClaimedSeparately() {

    final var lease = leaseOn("housekeeping-two-stores");

    assertTrue(lease.claimUntil("one-store", "first-node", inAnHour()));
    assertTrue(
        lease.claimUntil("another-store", "second-node", inAnHour()),
        "two databases may be house-kept by two nodes without bending each other's numbers");

  }

  @Test
  @DisplayName("A missing table ends the startup naming the table, the property and the artifact")
  public void aMissingTableIsReportedAtStartup() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> new JdbcHousekeepingLease(h2("housekeeping-missing"), TABLE).validateSchemaExists());

    assertTrue(failure.getMessage().contains(TABLE), failure.getMessage());
    assertTrue(
        failure.getMessage().contains(PhaseTwoOutboxProperties.CREATE_SCHEMA_PROPERTY),
        failure.getMessage());
    assertTrue(failure.getMessage().contains("io.vanillabp:vanillabp-schema"), failure.getMessage());
    // what is lost while it is missing
    assertTrue(failure.getMessage().contains("both tables grow"), failure.getMessage());

  }

  @Test
  @DisplayName("With the table in place the check passes, and a second start leaves it alone")
  public void anExistingTablePasses() {

    final var lease = leaseOn("housekeeping-created");

    assertDoesNotThrow(lease::validateSchemaExists);
    assertDoesNotThrow(lease::createSchemaIfNotExists);

  }

}
