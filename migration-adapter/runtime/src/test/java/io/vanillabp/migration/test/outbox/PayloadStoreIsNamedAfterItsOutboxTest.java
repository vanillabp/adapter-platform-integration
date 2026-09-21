package io.vanillabp.migration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Where the payloads of an outbox go when nobody said so: into a store named after that
 * outbox. Two applications which share a schema and keep themselves apart by renaming the
 * outbox would otherwise still share the payloads, and the first sign of it would be one
 * of them deleting rows of the other.
 * <p>
 * The rule is resolved in one place per store, so this test reads those two places: the
 * JDBC one both platforms run, and the MongoDB one both platforms read their collection
 * name from.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PayloadStoreIsNamedAfterItsOutboxTest {

  @Test
  @DisplayName("An application configuring nothing gets the payload table of the default outbox table")
  public void payloadTableOfTheDefaultOutboxTable() {

    final var properties = new PhaseTwoOutboxProperties();

    assertEquals(
        "VANILLABP_PHASE_TWO_OUTBOX_PAYLOAD",
        JdbcPhaseTwoOutboxStore.payloadTableName(properties));

  }

  @Test
  @DisplayName("A renamed outbox table renames the payload table with it")
  public void payloadTableFollowsTheOutboxTable() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.getJdbc().setTable("HOT_OUTBOX");

    assertEquals("HOT_OUTBOX_PAYLOAD", JdbcPhaseTwoOutboxStore.payloadTableName(properties));

  }

  @Test
  @DisplayName("A configured payload table is used as it stands, whatever the outbox table is called")
  public void aConfiguredPayloadTableStands() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.getJdbc().setTable("HOT_OUTBOX");
    properties.getJdbc().setPayloadTable("PAYLOADS_OF_MINE");

    assertEquals("PAYLOADS_OF_MINE", JdbcPhaseTwoOutboxStore.payloadTableName(properties));

  }

  @Test
  @DisplayName("An application configuring nothing gets the payload collection of the default outbox collection")
  public void payloadCollectionOfTheDefaultOutboxCollection() {

    final var properties = new PhaseTwoOutboxProperties();

    assertEquals(
        "vanillabp-phase-two-outbox-payloads",
        properties.getMongo().payloadCollectionName());

  }

  @Test
  @DisplayName("A renamed outbox collection renames the payload collection with it")
  public void payloadCollectionFollowsTheOutboxCollection() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.getMongo().setCollection("hot-outbox");

    assertEquals("hot-outbox-payloads", properties.getMongo().payloadCollectionName());

  }

  @Test
  @DisplayName("A configured payload collection is used as it stands, whatever the outbox collection is called")
  public void aConfiguredPayloadCollectionStands() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.getMongo().setCollection("hot-outbox");
    properties.getMongo().setPayloadCollection("payloads-of-mine");

    assertEquals("payloads-of-mine", properties.getMongo().payloadCollectionName());

  }

}
