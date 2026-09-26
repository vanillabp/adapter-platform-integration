package io.vanillabp.integration.test.values;

import java.math.BigDecimal;
import java.util.LinkedList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

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
import io.vanillabp.spi.service.TaskParam;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * An application which hands the BPMS a decimal, and which asks for a parameter of no
 * type at all, does not start until it says that it looked at both. This is the whole way
 * through a Spring Boot startup, where the unit tests of the check
 * ({@code PortableValuesCheckTest}) read the message alone. The same behaviour is held
 * for Quarkus by {@code UndeclaredValuesTest} and {@code DeclaredValuesTest} of the
 * deployment integration tests, because the two platforms start their applications
 * differently.
 * <p>
 * The declaration of a <code>&#64;TaskParam</code> can be written at four levels, and the
 * most specific of them wins. Every level is started here, because a level which binds
 * but is never read looks exactly like a level which works.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PortableValuesTest {

  private static final String PROCESS = "LoanApprovalProcess";

  private static final String WORKFLOW = "vanillabp.workflow-modules.test-module.workflows."
      + PROCESS;

  /**
   * The finished key the message hands out for the values of the aggregate.
   */
  private static final String DECLARED_VALUES = WORKFLOW
      + ".declared-aggregate-values";

  /**
   * The key at the task, which the message writes with a placeholder for the task
   * because it reports several tasks at once.
   */
  private static final String DECLARED_PARAMS_OF_THE_TASK = WORKFLOW
      + ".tasks.assessRisk.declared-task-params";

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
            final Supplier<T> work) {
          return work.get();
        }

        @Override
        public <T> T inCurrent(
            final Supplier<T> work) {
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

  private void boot(
      final Consumer<Throwable> assertions,
      final String... properties) {

    final var propertyValues = new LinkedList<String>(
        List.of("spring.config.location=classpath:application.yaml"));
    propertyValues.addAll(List.of(properties));
    this.contextRunner
        .withPropertyValues(propertyValues.toArray(String[]::new))
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withUserConfiguration(
            WorkflowModuleConfiguration.class,
            TestPersistenceConfiguration.class,
            OwnStoresConfiguration.class,
            LoanApprovalWorkflowService.class)
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
      if ((current.getMessage() != null) && current
          .getMessage()
          .contains("moves values between the application and the BPMS")) {
        return current.getMessage();
      }
      current = current.getCause();
    }
    throw new AssertionError("the startup did not report the values which do not travel: "
        + failure);

  }

  @Test
  @DisplayName("A decimal of the aggregate stops the startup, naming the value, the type, the direction and the key")
  public void aDecimalOfTheAggregateStopsTheStartup() {

    boot(
        failure -> {
          final var message = refusalIn(failure);
          Assertions.assertTrue(message.contains("'amount'"), message);
          Assertions.assertTrue(message.contains("java.math.BigDecimal"), message);
          Assertions.assertTrue(message.contains("TO the BPMS"), message);
          Assertions.assertTrue(message.contains("'"
              + PROCESS
              + "'"), message);
          Assertions.assertTrue(message.contains("'test-module'"), message);
          Assertions.assertTrue(message.contains(DECLARED_VALUES), "the finished property key");
        });

  }

  @Test
  @DisplayName("A @TaskParam of no type stops the startup even where the aggregate is declared")
  public void aTaskParamOfNoTypeStopsTheStartup() {

    boot(
        failure -> {
          final var message = refusalIn(failure);
          Assertions.assertFalse(message.contains("the aggregate value"), message);
          Assertions.assertTrue(message.contains("@TaskParam 'riskReport'"), message);
          Assertions.assertTrue(message.contains("java.lang.Object"), message);
          Assertions.assertTrue(message.contains("names no type at all"), message);
          Assertions
              .assertTrue(
                  message.contains(WORKFLOW
                      + ".tasks.<task>.declared-task-params"),
                  "the key at the task, with the task left open");
        },
        DECLARED_VALUES
            + "=amount");

  }

  @Test
  @DisplayName("The same application starts where both values are declared")
  public void bothDeclarationsStartTheApplication() {

    boot(
        failure -> Assertions.assertNull(failure, "both values were declared"),
        DECLARED_VALUES
            + "=amount",
        DECLARED_PARAMS_OF_THE_TASK
            + "=riskReport");

  }

  @Test
  @DisplayName("The declaration of a @TaskParam is read at the workflow")
  public void theDeclarationIsReadAtTheWorkflow() {

    boot(
        failure -> Assertions.assertNull(failure, "the workflow declared the parameter"),
        DECLARED_VALUES
            + "=amount",
        WORKFLOW
            + ".declared-task-params=riskReport");

  }

  @Test
  @DisplayName("The declaration of a @TaskParam is read at the workflow module")
  public void theDeclarationIsReadAtTheWorkflowModule() {

    boot(
        failure -> Assertions.assertNull(failure, "the workflow module declared the parameter"),
        DECLARED_VALUES
            + "=amount",
        "vanillabp.workflow-modules.test-module.declared-task-params=riskReport");

  }

  @Test
  @DisplayName("The declaration of a @TaskParam is read at the application")
  public void theDeclarationIsReadAtTheApplication() {

    boot(
        failure -> Assertions.assertNull(failure, "the application declared the parameter"),
        DECLARED_VALUES
            + "=amount",
        "vanillabp.declared-task-params=riskReport");

  }

  @Test
  @DisplayName("The task is the most specific level: what it says replaces what the workflow said")
  public void theTaskReplacesWhatTheWorkflowSaid() {

    boot(
        failure -> {
          final var message = refusalIn(failure);
          Assertions.assertTrue(message.contains("@TaskParam 'riskReport'"), message);
        },
        DECLARED_VALUES
            + "=amount",
        WORKFLOW
            + ".declared-task-params=riskReport",
        DECLARED_PARAMS_OF_THE_TASK
            + "=somethingElse");

  }

  /**
   * An aggregate which keeps one attribute back, so the full-sync check is happy and this
   * test is about the types of the values alone.
   */
  @Getter
  public static class LoanAggregate {

    private String id;

    private String region;

    private BigDecimal amount;

    @NoSyncWithBPMS
    private String internalNote;

  }

  @WorkflowService(
      workflowAggregateClass = LoanAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class LoanApprovalWorkflowService {

    @WorkflowTask(taskDefinition = "assessRisk")
    public void assessRisk(
        final LoanAggregate aggregate,
        @TaskParam("riskReport") final Object riskReport) {
    }

  }

}
