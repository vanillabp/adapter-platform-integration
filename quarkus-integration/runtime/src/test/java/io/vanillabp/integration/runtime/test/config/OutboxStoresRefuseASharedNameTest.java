package io.vanillabp.integration.runtime.test.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties.JdbcOutboxProperties;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties.MongoOutboxProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterProperties;
import io.vanillabp.integration.runtime.config.QuarkusMigrationAdapterTransformer;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Two stores of one database pointed at the same table or collection, asked of a
 * STARTING Quarkus application. The rule itself is the core's
 * ({@code OutboxStoresRefuseASharedNameTest} of the migration adapter holds it); what
 * this asks is whether the keys a Quarkus application writes reach that rule, over the
 * config mapping and the generated mapper onto the core model.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxStoresRefuseASharedNameTest {

  /**
   * The adapter of the application under test, as a Quarkus extension publishes it.
   */
  private static final String DUMMY_ADAPTER = "dummy";

  /**
   * Starts the application with these properties, the way the recorder does it while
   * Quarkus boots.
   *
   * @param properties What the application wrote below <code>vanillabp.</code>
   * @throws IllegalStateException Where the configuration cannot be made to work
   */
  private static void starting(
      final Map<String, String> properties) {

    final var config = new SmallRyeConfigBuilder()
        .withMapping(QuarkusMigrationAdapterProperties.class)
        .withSources(new PropertiesConfigSource(properties, "test", 500))
        .build();
    QuarkusMigrationAdapterTransformer
        .builder()
        .properties(config.getConfigMapping(QuarkusMigrationAdapterProperties.class))
        .propertyNames(config.getPropertyNames())
        .capabilities(List.of(QuarkusMigrationAdapterTransformer.PREFIX_ADAPTER_PACKAGE + DUMMY_ADAPTER))
        .build()
        .getAndValidatePropertiesConfigured(
            List.of(
                WorkflowModule
                    .builder()
                    .id("test-module")
                    .sourceUri(URI.create("file:/test"))
                    .global(true)
                    .build()),
            List.of(DUMMY_ADAPTER));

  }

  @Test
  @DisplayName("Two tables of one name end the start, naming both keys")
  public void twoTablesOfOneNameEndTheStart() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> starting(
            Map.of(
                "vanillabp.outbox.jdbc.table", "OUR_OUTBOX",
                "vanillabp.outbox.jdbc.payload-table", "OUR_OUTBOX")));

    final var message = failure.getMessage();
    assertTrue(message.contains(JdbcOutboxProperties.TABLE_PROPERTY), message);
    assertTrue(message.contains(JdbcOutboxProperties.PAYLOAD_TABLE_PROPERTY), message);
    assertTrue(message.contains("OUR_OUTBOX"), message);

  }

  @Test
  @DisplayName("The deliveries in the outbox collection end the start as well")
  public void theDeliveriesInTheOutboxCollectionEndTheStart() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> starting(
            Map.of(
                "vanillabp.outbox.mongo.delivery-collection",
                MongoOutboxProperties.DEFAULT_COLLECTION)));

    final var message = failure.getMessage();
    assertTrue(message.contains(MongoOutboxProperties.COLLECTION_PROPERTY), message);
    assertTrue(message.contains(MongoOutboxProperties.DELIVERY_COLLECTION_PROPERTY), message);

  }

  @Test
  @DisplayName("The deliveries in the outbox table end the start as well")
  public void theDeliveriesInTheOutboxTableEndTheStart() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> starting(
            Map.of(
                "vanillabp.outbox.jdbc.delivery-table",
                "VANILLABP_PHASE_TWO_OUTBOX")));

    final var message = failure.getMessage();
    assertTrue(message.contains(JdbcOutboxProperties.TABLE_PROPERTY), message);
    assertTrue(message.contains(JdbcOutboxProperties.DELIVERY_TABLE_PROPERTY), message);

  }

  @Test
  @DisplayName("Names of their own let the application start")
  public void namesOfTheirOwnAreFine() {

    assertDoesNotThrow(
        () -> starting(
            Map.of(
                "vanillabp.outbox.jdbc.table", "OUR_OUTBOX",
                "vanillabp.outbox.jdbc.payload-table", "OUR_OUTBOX_PAYLOAD",
                "vanillabp.outbox.jdbc.delivery-table", "OUR_DELIVERIES",
                "vanillabp.outbox.mongo.collection", "our-outbox",
                "vanillabp.outbox.mongo.payload-collection", "our-payloads",
                "vanillabp.outbox.mongo.delivery-collection", "our-deliveries")));

  }

}
