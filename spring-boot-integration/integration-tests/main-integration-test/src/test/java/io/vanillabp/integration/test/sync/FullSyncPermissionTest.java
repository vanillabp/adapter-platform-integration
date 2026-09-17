package io.vanillabp.integration.test.sync;

import java.util.List;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.NoSyncWithBPMS;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * An aggregate which keeps nothing back from the BPMS stops the Spring Boot startup until
 * its workflow allows exactly that. The same behaviour is held for Quarkus by
 * {@code FullSyncPermissionTest} of the deployment integration tests, because the two
 * platforms start their applications differently.
 */
@ExtendWith(SuppressOutputExtension.class)
public class FullSyncPermissionTest {

  private static final String PERMISSION = "vanillabp.workflow-modules.test-module.workflows.FullSyncProcess.allow-full-sync-with-bpms";

  /**
   * The stores of the application's own, so the boot gets past the outbox and the
   * transaction check: this context has no data source a platform default could use.
   */
  @Configuration
  static class OwnStoresConfiguration {

    @Bean
    PhaseTwoOutbox ownOutbox() {

      return call -> true;

    }

    @Bean
    TransactionRunner ownTransactionRunner() {

      return new TransactionRunner() {

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

  private void bootWith(
      final Class<?> workflowServiceClass,
      final java.util.function.Consumer<Throwable> assertions,
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
            OwnStoresConfiguration.class,
            workflowServiceClass)
        .withConfiguration(
            AutoConfigurations.of(
                DummyAdapterConfiguration.class, DummyAdapterProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class))
        .run(context -> assertions.accept(context.getStartupFailure()));

  }

  /**
   * @param failure The startup failure
   * @return The message of the refusal somewhere in its causes
   */
  private static String refusalIn(
      final Throwable failure) {

    var current = failure;
    while (current != null) {
      if ((current.getMessage() != null) && current.getMessage().contains("shares EVERY attribute")) {
        return current.getMessage();
      }
      current = current.getCause();
    }
    throw new AssertionError("the startup did not report the full sync: "
        + failure);

  }

  @Test
  @DisplayName("An aggregate which shares everything stops the startup, naming both ways on")
  public void anAggregateSharingEverythingStopsTheStartup() {

    bootWith(
        FullSyncWorkflowService.class,
        failure -> {
          final var message = refusalIn(failure);
          Assertions.assertTrue(message.contains(FullSyncAggregate.class.getName()), message);
          Assertions.assertTrue(message.contains("'FullSyncProcess'"), message);
          Assertions.assertTrue(message.contains("'test-module'"), message);
          Assertions.assertTrue(message.contains("cardNumber"), message);
          Assertions.assertTrue(message.contains("@NoSyncWithBPMS"), "the first way on");
          Assertions.assertTrue(message.contains(PERMISSION
              + ": true"), "the second way on, ready to copy");
        });

  }

  @Test
  @DisplayName("The permission at the workflow starts the same application")
  public void thePermissionAtTheWorkflowStartsIt() {

    bootWith(
        FullSyncWorkflowService.class,
        failure -> Assertions.assertNull(failure, "the workflow allowed the full sync"),
        PERMISSION
            + "=true");

  }

  @Test
  @DisplayName("The permission at the workflow module is refused, naming where it belongs")
  public void thePermissionAtTheWorkflowModuleDoesNotHelp() {

    bootWith(
        FullSyncWorkflowService.class,
        failure -> {
          var current = failure;
          while ((current != null) && ((current.getMessage() == null) || !current
              .getMessage()
              .contains("allowed at the workflow and nowhere else"))) {
            current = current.getCause();
          }
          Assertions.assertNotNull(current, "the misplaced permission was not reported: "
              + failure);
          Assertions
              .assertTrue(
                  current.getMessage().contains("vanillabp.workflow-modules.test-module.allow-full-sync-with-bpms"),
                  current.getMessage());
        },
        "vanillabp.workflow-modules.test-module.allow-full-sync-with-bpms=true");

  }

  @Test
  @DisplayName("An aggregate keeping one attribute back starts without a permission")
  public void anAggregateKeepingSomethingBackStarts() {

    bootWith(
        MinimalSyncWorkflowService.class,
        failure -> Assertions.assertNull(failure, "one @NoSyncWithBPMS is enough"));

  }

  @Getter
  public static class FullSyncAggregate {

    private String id;

    private String customer;

    private String cardNumber;

  }

  @Getter
  public static class MinimalSyncAggregate {

    private String id;

    private String customer;

    @NoSyncWithBPMS
    private String cardNumber;

  }

  @WorkflowService(
      workflowAggregateClass = FullSyncAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "FullSyncProcess"))
  public static class FullSyncWorkflowService {

    @WorkflowTask
    public void approve(
        final FullSyncAggregate aggregate) {
    }

  }

  @WorkflowService(
      workflowAggregateClass = MinimalSyncAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "FullSyncProcess"))
  public static class MinimalSyncWorkflowService {

    @WorkflowTask
    public void approve(
        final MinimalSyncAggregate aggregate) {
    }

  }

}
