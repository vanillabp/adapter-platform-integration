package io.vanillabp.integration.test.adapter;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.adapter.dummy.springboot.processservice.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Startup report of the inbound idempotency (story 51): an adapter which may hand the
 * same task out again needs a store to remember processed deliveries in. Unlike the
 * outbox a missing store does NOT fail the boot - without it VanillaBP behaves as it did
 * before the feature existed - so what is pinned here is the WARNING naming both ways
 * out, and that switching the feature off silences it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TaskDeliveryLogStartupValidationTest {

  /**
   * The stores and the transaction of the application's own, so the boot gets past the
   * outbox and transaction validations: the dummy adapter needs a two-phase commit here,
   * and this context has no data source a platform default could use. Nothing is
   * dispatched in these tests, and the runner is a pass-through - what is pinned here is a
   * log message, not a transaction.
   */
  @org.springframework.context.annotation.Configuration
  static class OwnOutboxConfiguration {

    @org.springframework.context.annotation.Bean
    io.vanillabp.integration.spi.PhaseTwoOutbox ownOutbox() {

      return call -> true;

    }

    @org.springframework.context.annotation.Bean
    io.vanillabp.integration.spi.TransactionRunner ownTransactionRunner() {

      return new io.vanillabp.integration.spi.TransactionRunner() {

        @Override
        public <T> T requireNew(
            final java.util.function.Supplier<T> work) {
          return work.get();
        }

        @Override
        public <T> T inCurrent(
            final java.util.function.Supplier<T> work) {
          return work.get();
        }

        @Override
        public boolean isRollbackOnly() {
          return false;
        }

      };

    }

  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  /**
   * The messages the core logged while the given work ran.
   */
  private List<String> loggedByCore(
      final Runnable work) {

    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(MigrationProcessService.class);
    logger.addAppender(logWatcher);
    try {
      work.run();
    } finally {
      logger.detachAppender(logWatcher);
      logWatcher.stop();
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(ch.qos.logback.classic.Level.WARN))
        .map(event -> event.getFormattedMessage())
        .toList();

  }

  private void bootWith(
      final Runnable assertions,
      final String... properties) {

    final var propertyValues = new java.util.LinkedList<String>(
        List.of("spring.config.location=classpath:application.yaml"));
    propertyValues.addAll(List.of(properties));
    this.contextRunner
        .withPropertyValues(propertyValues.toArray(String[]::new))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(
            WorkflowModuleConfiguration.class,
            TestPersistenceConfiguration.class,
            OwnOutboxConfiguration.class)
        .withConfiguration(
            AutoConfigurations.of(
                DummyAdapterConfiguration.class, DummyAdapterProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> {
          Assertions.assertNull(
              context.getStartupFailure(),
              "a missing delivery log must never fail the boot");
          assertions.run();
        });

  }

  @Test
  public void anAdapterRepeatingDeliveriesWithoutAStoreIsReported() {

    // the dummy adapter stands in for a remote BPMS: it needs a two-phase commit for
    // starting workflows AND may deliver a task more than once. There is no data source
    // in this context, so no delivery log can be resolved
    final var messages = loggedByCore(
        () -> bootWith(
            () -> {
            },
            "dummy-adapter.two-phase-commit=true"));

    // one warning per BPMN process, each naming its own aggregate - the test application
    // has several, so the one of this assertion is picked by the aggregate it names
    final var message = messages
        .stream()
        .filter(candidate -> candidate.contains("more than once"))
        .filter(candidate -> candidate.contains(Aggregate.class.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no warning about repeated deliveries, logged: "
            + messages));
    // it names the BPMS, the SPI to implement and the property to set instead
    Assertions.assertTrue(message.contains("Adapter 'test'"));
    Assertions.assertTrue(message.contains("TaskDeliveryLog"));
    Assertions.assertTrue(message.contains("TaskDeliveryLogAware"));
    Assertions.assertTrue(message.contains("vanillabp.adapters.test.deduplicate-deliveries"));

  }

  @Test
  public void switchingTheFeatureOffSilencesTheReport() {

    final var messages = loggedByCore(
        () -> bootWith(
            () -> {
            },
            "dummy-adapter.two-phase-commit=true",
            "vanillabp.adapters.test.deduplicate-deliveries=false"));

    Assertions.assertTrue(
        messages.stream().noneMatch(message -> message.contains("more than once")),
        "an application stating that its handlers are idempotent is not warned, logged: "
            + messages);

  }

}
