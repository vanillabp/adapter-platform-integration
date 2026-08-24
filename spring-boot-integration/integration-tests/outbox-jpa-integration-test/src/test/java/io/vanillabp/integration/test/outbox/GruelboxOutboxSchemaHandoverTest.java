package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.gruelbox.transactionoutbox.TransactionOutbox;

import io.vanillabp.integration.outbox.gruelbox.GruelboxPhaseTwoOutboxAutoConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * On Spring Boot the phase-two outbox is gruelbox, and its table
 * <code>TXNO_OUTBOX</code> is the one table a schema handover does NOT get from
 * <code>io.vanillabp:vanillabp-schema</code> - the schema belongs to gruelbox. Switching
 * VanillaBP's table creation off switches gruelbox's migrator off with it, and a custom table
 * name does the same silently, so the table's existence is verified at startup exactly like
 * VanillaBP's own two. Without that check an application which forgot the table boots cleanly
 * and fails at the first workflow it starts.
 */
@ExtendWith(SuppressOutputExtension.class)
public class GruelboxOutboxSchemaHandoverTest {

  private static ApplicationContextRunner applicationOn(
      final String database,
      final String... properties) {

    final var configuration = new String[properties.length + 2];
    configuration[0] = "spring.datasource.url=jdbc:h2:mem:%s;DB_CLOSE_DELAY=-1".formatted(database);
    configuration[1] = "spring.jpa.hibernate.ddl-auto=none";
    System.arraycopy(properties, 0, configuration, 2, properties.length);

    return new ApplicationContextRunner()
        .withPropertyValues(configuration)
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class, DataSourceTransactionManagerAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class, GruelboxPhaseTwoOutboxAutoConfiguration.class));

  }

  private static String bootFailureOf(
      final AssertableApplicationContext context) {

    final var failure = context.getStartupFailure();
    assertNotNull(failure, "the boot has to end with a guiding message");
    var cause = failure;
    while (cause.getCause() != null) {
      cause = cause.getCause();
    }
    return String.valueOf(cause.getMessage());

  }

  @Test
  @DisplayName("A handed-over schema without the outbox table ends the boot naming table, property and source")
  public void aMissingOutboxTableEndsTheBoot() {

    applicationOn("schema-handover-missing", "vanillabp.outbox.create-schema=false")
        .run(context -> {

          final var message = bootFailureOf(context);

          assertTrue(message.contains("TXNO_OUTBOX"), message);
          assertTrue(message.contains("vanillabp.outbox.create-schema"), message);
          // the statements are gruelbox's, so the message must not send anybody to
          // VanillaBP's schema artifact for them
          assertTrue(message.contains("gruelbox"), message);
          assertTrue(
              message.contains("Creating the tables with Liquibase or Flyway"),
              message);
          // the remedy has to name what actually produces those statements
          assertTrue(message.contains("DefaultPersistor.writeSchema"), message);

        });

  }

  @Test
  @DisplayName("The table gruelbox's own migrator creates satisfies the check")
  public void aTableCreatedByGruelboxPasses() {

    // first boot creates the table the way an application which never handed the schema
    // over gets it ...
    applicationOn("schema-handover-created")
        .run(context -> assertNull(context.getStartupFailure(), "the migrating boot has to work"));

    // ... and that is what the check accepts on the next boot of the same database
    applicationOn("schema-handover-created", "vanillabp.outbox.create-schema=false")
        .run(context -> {

          assertNull(context.getStartupFailure(), "the table exists, so nothing changes");
          assertNotNull(context.getBean(TransactionOutbox.class));

        });

  }

  @Test
  @DisplayName("A custom table name is checked as well - it switches the migration off silently")
  public void aMissingCustomOutboxTableEndsTheBoot() {

    applicationOn(
        "schema-handover-custom",
        "vanillabp.outbox.create-schema=true",
        "vanillabp.outbox.jdbc.table=MY_OUTBOX")
        .run(context -> {

          final var message = bootFailureOf(context);

          assertTrue(message.contains("MY_OUTBOX"), message);
          assertTrue(message.contains("vanillabp.outbox.jdbc.table"), message);
          assertTrue(message.contains("gruelbox"), message);

        });

  }

}
