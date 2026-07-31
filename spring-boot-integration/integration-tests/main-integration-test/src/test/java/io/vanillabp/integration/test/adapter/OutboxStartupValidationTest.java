package io.vanillabp.integration.test.adapter;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.adapter.dummy.springboot.processservice.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.spi.PhaseTwoOutbox;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Startup validation of the phase-two outbox (story 26i): a process whose
 * first-priority adapter requires a two-phase commit needs a resolvable outbox AT
 * STARTUP - the boot fails with a guiding message naming the remedies instead of
 * surfacing the gap at the first workflow start. A process whose first-priority
 * adapter does NOT require a two-phase commit boots without any outbox
 * materialized (an application using only an embedded BPMS must not be forced to
 * have an outbox store).
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxStartupValidationTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  @Test
  public void twoPhaseCommitAdapterWithoutOutboxFailsAtStartupWithRemedies() {

    this.contextRunner
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "dummy-adapter.two-phase-commit=true")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(WorkflowModuleConfiguration.class, TestPersistenceConfiguration.class)
        .withConfiguration(
            AutoConfigurations.of(
                DummyAdapterConfiguration.class, DummyAdapterProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNotNull(context.getStartupFailure(), "boot has to fail with a guiding message");

          var cause = (Throwable) context.getStartupFailure();
          while (cause.getCause() != null) {
            cause = cause.getCause();
          }
          final var message = String.valueOf(cause.getMessage());
          Assertions.assertTrue(
              message.contains("requires a two-phase commit"),
              "expected the guiding message but got: "
                  + message);
          // the message names the aggregate and ALL remedies
          Assertions.assertTrue(message.contains(Aggregate.class.getName()));
          Assertions.assertTrue(message.contains("spring-boot-starter-data-jpa"));
          Assertions.assertTrue(message.contains("spring-boot-starter-data-mongodb"));
          Assertions.assertTrue(message.contains("PhaseTwoOutbox"));
          Assertions.assertTrue(message.contains("PhaseTwoOutboxAware"));

        });

  }

  @Test
  public void adapterWithoutTwoPhaseCommitBootsWithoutAnyOutbox() {

    this.contextRunner
        .withPropertyValues(
            "spring.config.location=classpath:application.yaml",
            "dummy-adapter.two-phase-commit=false")
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(WorkflowModuleConfiguration.class, TestPersistenceConfiguration.class)
        .withConfiguration(
            AutoConfigurations.of(
                DummyAdapterConfiguration.class, DummyAdapterProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {

          Assertions.assertNull(context.getStartupFailure(), "context should start");
          // nothing outbox-related materializes for adapters not needing a
          // two-phase commit
          Assertions.assertTrue(context.getBeansOfType(PhaseTwoOutbox.class).isEmpty());

        });

  }

}
