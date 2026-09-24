package io.vanillabp.migration.test.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties.JdbcOutboxProperties;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties.MongoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxStore;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two stores of one database pointed at the same table or collection. Six names can be
 * set and nothing about them says that they have to differ, so the boot says it: each of
 * the two stores would read, count and delete what the other wrote.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxStoresRefuseASharedNameTest {

  @Test
  @DisplayName("An application which sets no name at all passes")
  public void theDefaultsAreApart() {

    assertDoesNotThrow(() -> new PhaseTwoOutboxProperties().validateStoreNames());

  }

  @Test
  @DisplayName("An absent section costs neither the defaults nor the check")
  public void anAbsentSectionIsTheDefaultSection() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.setJdbc(null);
    properties.setMongo(null);

    assertDoesNotThrow(properties::validateStoreNames);

  }

  @Test
  @DisplayName("The payload table must not be the outbox table")
  public void twoTablesOfOneNameFailTheBoot() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.getJdbc().setTable("OUR_OUTBOX");
    properties.getJdbc().setPayloadTable("OUR_OUTBOX");

    final var message = assertThrows(IllegalStateException.class, properties::validateStoreNames)
        .getMessage();

    assertTrue(message.contains(JdbcOutboxProperties.TABLE_PROPERTY), message);
    assertTrue(message.contains(JdbcOutboxProperties.PAYLOAD_TABLE_PROPERTY), message);
    assertTrue(message.contains("OUR_OUTBOX"), "names what both keys say: "
        + message);
    assertTrue(message.contains("_PAYLOAD"), "and the suffix an unset payload table gets: "
        + message);

  }

  @Test
  @DisplayName("A payload table which is the outbox table of the DEFAULT is caught as well")
  public void oneKeyIsEnoughToMeetTheOther() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.getJdbc().setPayloadTable(JdbcPhaseTwoOutboxStore.DEFAULT_TABLE_NAME);

    assertThrows(IllegalStateException.class, properties::validateStoreNames);

  }

  @Test
  @DisplayName("A relational database reads a table name in capitals, so the spelling does not save it")
  public void twoSpellingsOfOneTableAreOneTable() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.getJdbc().setTable("our_outbox");
    properties.getJdbc().setPayloadTable("OUR_OUTBOX");

    final var message = assertThrows(IllegalStateException.class, properties::validateStoreNames)
        .getMessage();

    assertTrue(message.contains("our_outbox"), "each key is quoted as the application wrote it: "
        + message);
    assertTrue(message.contains("OUR_OUTBOX"), message);

  }

  @Test
  @DisplayName("The deliveries must not lie in the outbox table")
  public void theDeliveriesKeepTheirOwnTable() {

    final var properties = new PhaseTwoOutboxProperties();
    properties
        .getJdbc()
        .setDeliveryTable(JdbcPhaseTwoOutboxStore.DEFAULT_TABLE_NAME);

    final var message = assertThrows(IllegalStateException.class, properties::validateStoreNames)
        .getMessage();

    assertTrue(message.contains(JdbcOutboxProperties.TABLE_PROPERTY), message);
    assertTrue(message.contains(JdbcOutboxProperties.DELIVERY_TABLE_PROPERTY), message);
    assertTrue(message.contains(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME), "and the way out names the default: "
        + message);

  }

  @Test
  @DisplayName("The deliveries must not lie in the outbox collection")
  public void theDeliveriesKeepTheirOwnCollection() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.getMongo().setDeliveryCollection(MongoOutboxProperties.DEFAULT_COLLECTION);

    final var message = assertThrows(IllegalStateException.class, properties::validateStoreNames)
        .getMessage();

    assertTrue(message.contains(MongoOutboxProperties.COLLECTION_PROPERTY), message);
    assertTrue(message.contains(MongoOutboxProperties.DELIVERY_COLLECTION_PROPERTY), message);
    assertTrue(message.contains(MongoOutboxProperties.DEFAULT_COLLECTION), message);

  }

  @Test
  @DisplayName("The payloads must not lie in the collection of the deliveries")
  public void thePayloadsKeepTheirOwnCollection() {

    final var properties = new PhaseTwoOutboxProperties();
    properties
        .getMongo()
        .setPayloadCollection(MongoOutboxProperties.DEFAULT_DELIVERY_COLLECTION);

    final var message = assertThrows(IllegalStateException.class, properties::validateStoreNames)
        .getMessage();

    assertTrue(message.contains(MongoOutboxProperties.PAYLOAD_COLLECTION_PROPERTY), message);
    assertTrue(message.contains(MongoOutboxProperties.DELIVERY_COLLECTION_PROPERTY), message);

  }

  @Test
  @DisplayName("MongoDB tells two spellings of a collection apart, and so does the check")
  public void twoSpellingsOfOneCollectionAreTwoCollections() {

    final var properties = new PhaseTwoOutboxProperties();
    properties
        .getMongo()
        .setDeliveryCollection(MongoOutboxProperties.DEFAULT_COLLECTION.toUpperCase(Locale.ROOT));

    assertDoesNotThrow(properties::validateStoreNames);

  }

  @Test
  @DisplayName("A table and a collection of one name are two places, in two databases")
  public void theTwoDatabasesAreCheckedApart() {

    final var properties = new PhaseTwoOutboxProperties();
    properties.getJdbc().setTable("one-name");
    properties.getMongo().setCollection("one-name");

    assertDoesNotThrow(properties::validateStoreNames);

  }

}
