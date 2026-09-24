package io.vanillabp.integration.test.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.adapter.AdapterConfigurationBase;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties.JdbcOutboxProperties;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties.MongoOutboxProperties;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModule;
import io.vanillabp.integration.workflowmodule.WorkflowModules;

/**
 * Two stores of one database pointed at the same table or collection, asked of a
 * BOOTING Spring Boot application. The rule itself is the core's
 * ({@code OutboxStoresRefuseASharedNameTest} of the migration adapter holds it); what
 * this asks is whether an application which writes those keys gets the message before
 * the first store works on the wrong place.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxStoresRefuseASharedNameTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withUserConfiguration(ClasspathFactsConfiguration.class)
      .withConfiguration(
          AutoConfigurations.of(SpringBootMigrationAdapterAutoConfiguration.class))
      .withPropertyValues(
          "vanillabp.resources-location=classpath*:vanillabp-processes",
          "vanillabp.adapters.test.type=dummy");

  /**
   * What a scan would find in an application: one workflow module and one adapter. The
   * configuration under test is about neither of them, and without both the boot ends
   * before it reads the outbox section.
   */
  @Configuration
  static class ClasspathFactsConfiguration {

    @Bean
    WorkflowModules workflowModules() {

      return new WorkflowModules(
          List.of(
              WorkflowModule
                  .builder()
                  .id("test-module")
                  .sourceUri("file:/test")
                  .build()));

    }

    @Bean
    AdapterConfigurationBase testAdapterConfiguration() {

      return new AdapterConfigurationBase() {
        @Override
        public String getAdapterType() {

          return "dummy";

        }
      };

    }

  }

  @Test
  @DisplayName("Two tables of one name end the boot, naming both keys")
  public void twoTablesOfOneNameEndTheBoot() {

    contextRunner
        .withPropertyValues(
            "vanillabp.outbox.jdbc.table=OUR_OUTBOX",
            "vanillabp.outbox.jdbc.payload-table=OUR_OUTBOX")
        .run(context -> {

          assertNotNull(context.getStartupFailure(), "expected the boot to end");
          final var message = rootMessage(context.getStartupFailure());
          assertTrue(message.contains(JdbcOutboxProperties.TABLE_PROPERTY), message);
          assertTrue(message.contains(JdbcOutboxProperties.PAYLOAD_TABLE_PROPERTY), message);
          assertTrue(message.contains("OUR_OUTBOX"), message);

        });

  }

  @Test
  @DisplayName("The deliveries in the outbox collection end the boot as well")
  public void theDeliveriesInTheOutboxCollectionEndTheBoot() {

    contextRunner
        .withPropertyValues(
            "vanillabp.outbox.mongo.delivery-collection="
                + MongoOutboxProperties.DEFAULT_COLLECTION)
        .run(context -> {

          assertNotNull(context.getStartupFailure(), "expected the boot to end");
          final var message = rootMessage(context.getStartupFailure());
          assertTrue(message.contains(MongoOutboxProperties.COLLECTION_PROPERTY), message);
          assertTrue(message.contains(MongoOutboxProperties.DELIVERY_COLLECTION_PROPERTY), message);

        });

  }

  @Test
  @DisplayName("Names of their own let the application boot")
  public void namesOfTheirOwnAreFine() {

    contextRunner
        .withPropertyValues(
            "vanillabp.outbox.jdbc.table=OUR_OUTBOX",
            "vanillabp.outbox.jdbc.payload-table=OUR_OUTBOX_PAYLOAD",
            "vanillabp.outbox.mongo.collection=our-outbox",
            "vanillabp.outbox.mongo.payload-collection=our-payloads",
            "vanillabp.outbox.mongo.delivery-collection=our-deliveries")
        .run(context -> assertNull(context.getStartupFailure(), "the boot has to go through"));

  }

  private static String rootMessage(
      final Throwable throwable) {

    var cause = throwable;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return cause.getMessage();

  }

}
