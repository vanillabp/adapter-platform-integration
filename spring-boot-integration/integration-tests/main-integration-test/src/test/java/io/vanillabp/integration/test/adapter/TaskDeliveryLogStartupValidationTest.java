package io.vanillabp.integration.test.adapter;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.deployment.DeploymentAutoConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.sample.Aggregate;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;

/**
 * Startup report of the inbound idempotency: an adapter which may hand the
 * same task out again needs a store to remember processed deliveries in. Unlike the
 * outbox a missing store does NOT fail the boot - without it VanillaBP behaves as it did
 * before the feature existed - so what is pinned here is the WARNING naming both ways
 * out, and that switching the feature off silences it.
 * <p>
 * The warning is read where a developer reads it: in the block the start writes at its
 * end. Writing that block takes the lifecycle bean which ends the start, so the
 * platform's deployment auto-configuration is part of every context here.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TaskDeliveryLogStartupValidationTest {

  /**
   * The stores and the transaction of the application's own, so the boot gets past the
   * outbox and transaction validations: this context has no data source a platform
   * default could use. Nothing is
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

  /**
   * An application's own store, written before the release check existed: it
   * inherits the SPI's default which deletes nothing.
   */
  @org.springframework.context.annotation.Configuration
  static class LegacyDeliveryLogConfiguration {

    @org.springframework.context.annotation.Bean
    io.vanillabp.integration.spi.TaskDeliveryLog legacyDeliveryLog() {

      return new io.vanillabp.integration.spi.TaskDeliveryLog() {

        @Override
        public java.util.Optional<io.vanillabp.integration.spi.TaskDelivery> recordedDelivery(
            final String deliveryKey) {
          return java.util.Optional.empty();
        }

        @Override
        public boolean record(
            final io.vanillabp.integration.spi.TaskDelivery delivery) {
          return true;
        }

      };

    }

  }

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  /**
   * Boots an application and returns the block its start wrote.
   *
   * @param output What the test printed so far, which is where the block lands
   * @param userConfigurations What this application brings besides the common beans
   * @param properties The properties this application is configured with
   * @return Everything printed by this test, the block included
   */
  private String theBlockOf(
      final CapturedOutput output,
      final Class<?>[] userConfigurations,
      final String... properties) {

    final var propertyValues = new java.util.LinkedList<String>(
        List.of("spring.config.location=classpath:application.yaml"));
    propertyValues.addAll(List.of(properties));
    final var configurations = new java.util.LinkedList<Class<?>>(
        List.of(
            WorkflowModuleConfiguration.class,
            TestPersistenceConfiguration.class,
            OwnOutboxConfiguration.class,
            SampleWorkflowService.class));
    configurations.addAll(List.of(userConfigurations));
    this.contextRunner
        .withPropertyValues(propertyValues.toArray(String[]::new))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(configurations.toArray(Class<?>[]::new))
        .withConfiguration(
            AutoConfigurations.of(
                DummyAdapterConfiguration.class, DummyAdapterProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class,
                DeploymentAutoConfiguration.class))
        .run(context -> Assertions.assertNull(
            context.getStartupFailure(),
            "a missing delivery log must never fail the boot"));

    final var printed = output.getAllOfThisTest();
    // an assertion about something the block does NOT say is worth nothing where no block
    // was written at all
    Assertions.assertTrue(
        printed.contains("looked at this application and found"),
        "the start has to write its block, printed: "
            + printed);
    return printed;

  }

  @Test
  public void anAdapterRepeatingDeliveriesWithoutAStoreIsReported(
      final CapturedOutput output) {

    // the dummy adapter stands in for a remote BPMS: it may deliver a task more than
    // once. There is no data source in this context, so no delivery log can be resolved
    final var block = theBlockOf(
        output,
        new Class<?>[0],
        "dummy-adapter.at-least-once-delivery=true");

    // the warning names the aggregate it is about...
    Assertions.assertTrue(
        block.contains("more than once, but no TaskDeliveryLog is available for aggregate '"
            + Aggregate.class.getName()),
        "no warning about repeated deliveries, printed: "
            + block);
    // ...and stands under the process and the adapter it belongs to
    Assertions.assertTrue(
        block.contains("process 'SampleWorkflowService' of workflow module 'test-module', adapter 'test'"),
        block);
    // it names the SPI to implement and the property to set instead
    Assertions.assertTrue(block.contains("TaskDeliveryLogAware"), block);
    Assertions.assertTrue(block.contains("vanillabp.adapters.test.deduplicate-deliveries"), block);

  }

  @Test
  public void aStoreWithoutTheReleaseIsReportedWhereTheReleaseIsSwitchedOn(
      final CapturedOutput output) {

    // The application asked for records to disappear when a workflow ends, and
    // its own store cannot do it - which is a misconfiguration, not a missing feature
    final var block = theBlockOf(
        output,
        new Class<?>[]{
            LegacyDeliveryLogConfiguration.class
        },
        "dummy-adapter.at-least-once-delivery=true",
        "vanillabp.delivery.release-on-workflow-end=true");

    Assertions.assertTrue(
        block.contains("does not implement 'releaseRecordsOf'"),
        "no warning about the missing release, printed: "
            + block);
    Assertions.assertTrue(block.contains("TaskDeliveryLog"), block);
    Assertions.assertTrue(block.contains("delivery.release-on-workflow-end"), block);

  }

  @Test
  public void aStoreWithoutTheReleaseIsSilentWhereNobodyAskedForIt(
      final CapturedOutput output) {

    final var block = theBlockOf(
        output,
        new Class<?>[]{
            LegacyDeliveryLogConfiguration.class
        },
        "dummy-adapter.at-least-once-delivery=true");

    Assertions.assertFalse(
        block.contains("releaseRecordsOf"),
        "an application which did not ask for the release must not be told about it, printed: "
            + block);

  }

  @Test
  public void switchingTheFeatureOffSilencesTheReport(
      final CapturedOutput output) {

    final var block = theBlockOf(
        output,
        new Class<?>[0],
        "dummy-adapter.at-least-once-delivery=true",
        "vanillabp.adapters.test.deduplicate-deliveries=false");

    Assertions.assertFalse(
        block.contains("more than once"),
        "an application stating that its handlers are idempotent is not warned, printed: "
            + block);

  }

}
