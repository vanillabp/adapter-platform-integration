package io.vanillabp.integration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;

import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterConfiguration;
import io.vanillabp.bpmsdouble.springboot.DummyAdapterProcessServiceConfiguration;
import io.vanillabp.integration.adapter.migration.workflowtask.AProcessBelongsToOneAggregate;
import io.vanillabp.integration.adapter.migration.workflowtask.AProcessBelongsToOneAggregate.Declaration;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.processservice.SpringBootMigrationAdapterAutoConfiguration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.TestPersistenceConfiguration;
import io.vanillabp.integration.test.TestTransactionRunnerConfiguration;
import io.vanillabp.integration.test.WorkflowModuleConfiguration;
import io.vanillabp.integration.test.deployment.DeploymentTest;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowmodule.WorkflowModuleAutoConfiguration;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * A BPMN process belongs to exactly one workflow aggregate, on Spring Boot. Classes of
 * different aggregates declaring the same process end the start, whichever way each of them
 * declares it and whichever of them is found first. Classes of one aggregate may still share a
 * process.
 * <p>
 * The order of the classes is the order of the sources handed to the application, which is
 * the order the bean definitions are registered in and so the order the workflow services are
 * found in.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AProcessBelongsToOneAggregateTest {

  private static final String MODULE = "test-module";

  /**
   * The process the double deploys, so the case where the start succeeds has a model.
   */
  private static final String SHARED_PROCESS = "DummyProcess";

  private static final String CALLED_STEP = "CalledStep";

  public static class LoanAggregate {

    String id = "4711";

  }

  public static class CustomerAggregate {

    String id = "0815";

  }

  @Service
  @WorkflowService(
      workflowAggregateClass = LoanAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = SHARED_PROCESS))
  public static class StartsTheProcess {

    @WorkflowTask(taskDefinition = "processTask")
    public void processTask(
        final LoanAggregate aggregate) {
    }

  }

  @Service
  @WorkflowService(
      workflowAggregateClass = CustomerAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "CustomerOnboarding"),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = SHARED_PROCESS))
  public static class CallsTheProcess {
  }

  @Service
  @WorkflowService(
      workflowAggregateClass = CustomerAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = SHARED_PROCESS))
  public static class AlsoStartsTheProcess {
  }

  @Service
  @WorkflowService(
      workflowAggregateClass = LoanAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "LoanApproval"),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = SHARED_PROCESS))
  public static class AlsoCallsTheProcess {
  }

  /**
   * Shares the process and its called step with {@link StartsTheProcess}, on the same
   * aggregate. Its handlers are merged with the ones of that class.
   */
  @Service
  @WorkflowService(
      workflowAggregateClass = LoanAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = SHARED_PROCESS),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = CALLED_STEP))
  public static class SecondHalfOfTheProcess {
  }

  @Service
  @WorkflowService(
      workflowAggregateClass = LoanAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = SHARED_PROCESS),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = CALLED_STEP))
  public static class ThirdPartOfTheProcess {
  }

  @Configuration
  static class AggregatePersistenceConfiguration {

    @Bean
    AggregatePersistenceAware<LoanAggregate> loanAggregatePersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<LoanAggregate> getAggregateClass() {
          return LoanAggregate.class;
        }

        @Override
        public LoanAggregate save(
            final LoanAggregate aggregate) {
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final LoanAggregate aggregate) {
          return aggregate.id;
        }

      };

    }

    @Bean
    AggregatePersistenceAware<CustomerAggregate> customerAggregatePersistence() {

      return new AggregatePersistenceAware<>() {

        @Override
        public Class<CustomerAggregate> getAggregateClass() {
          return CustomerAggregate.class;
        }

        @Override
        public CustomerAggregate save(
            final CustomerAggregate aggregate) {
          return aggregate;
        }

        @Override
        public Object getAggregateId(
            final CustomerAggregate aggregate) {
          return aggregate.id;
        }

      };

    }

  }

  /**
   * Stands in for the BPMN model of the shared process: one task, which
   * {@link StartsTheProcess} has the handler of.
   */
  @Configuration
  static class SharedProcessWithOneTask {

    @Bean
    DummyTaskWiringSource taskWiringSource() {

      return (
          adapterId,
          workflowModuleId,
          bpmnProcessId) -> SHARED_PROCESS.equals(bpmnProcessId)
              ? List.of(new BpmnTaskSpec("Activity_Process", "processTask"))
              : List.of();

    }

  }

  /**
   * Every remote call of a workflow goes through an outbox, and this test sends nothing.
   */
  @Configuration
  static class OutboxWhichIsNeverUsed {

    @Bean
    PhaseTwoOutbox outboxWhichIsNeverUsed() {

      return call -> true;

    }

  }

  private static SpringApplicationBuilder application(
      final Class<?>... workflowServices) {

    return new SpringApplicationBuilder(Stream
        .concat(
            Stream.of(
                DummyAdapterConfiguration.class,
                DummyAdapterProcessServiceConfiguration.class,
                WorkflowModuleAutoConfiguration.class,
                SpringBootMigrationAdapterAutoConfiguration.class,
                TestPersistenceConfiguration.class,
                TestTransactionRunnerConfiguration.class,
                WorkflowModuleConfiguration.class,
                DeploymentTest.TestConfig.class,
                AggregatePersistenceConfiguration.class,
                SharedProcessWithOneTask.class,
                OutboxWhichIsNeverUsed.class),
            Stream.of(workflowServices))
        .toArray(Class<?>[]::new));

  }

  /**
   * Starts the application and returns the message which ended the start, wherever Spring
   * wrapped it.
   */
  private static String refusalOfAStartWith(
      final Class<?>... workflowServices) {

    final var failure = assertThrows(
        Exception.class,
        () -> {
          try (var context = application(workflowServices).run()) {
            // the start has to fail
          }
        });
    Throwable current = failure;
    while (current != null) {
      if ((current.getMessage() != null) && current.getMessage()
          .startsWith("The BPMN process '%s'".formatted(SHARED_PROCESS))) {
        return current.getMessage();
      }
      current = current.getCause();
    }
    throw new AssertionError("the start failed for another reason", failure);

  }

  private static String expectedRefusal(
      final Declaration... declarations) {

    return AProcessBelongsToOneAggregate
        .refusalOf(List.of(declarations))
        .orElseThrow();

  }

  private static Declaration asBpmnProcess(
      final Class<?> workflowService,
      final Class<?> aggregate) {

    return new Declaration(MODULE, SHARED_PROCESS, workflowService.getName(), aggregate.getName(), true);

  }

  private static Declaration asSecondary(
      final Class<?> workflowService,
      final Class<?> aggregate) {

    return new Declaration(MODULE, SHARED_PROCESS, workflowService.getName(), aggregate.getName(), false);

  }

  @Test
  @DisplayName("One class starts the process, a class of another aggregate calls it: the start ends, in both orders")
  public void startedByOneAggregateAndCalledByAnother() {

    final var startingFirst = refusalOfAStartWith(StartsTheProcess.class, CallsTheProcess.class);
    final var callingFirst = refusalOfAStartWith(CallsTheProcess.class, StartsTheProcess.class);

    assertEquals(startingFirst, callingFirst, "the order of the classes changed the message");
    assertEquals(
        expectedRefusal(
            asBpmnProcess(StartsTheProcess.class, LoanAggregate.class),
            asSecondary(CallsTheProcess.class, CustomerAggregate.class)),
        startingFirst);
    assertTrue(startingFirst.contains(StartsTheProcess.class.getName()
        + " (workflow aggregate "
        + LoanAggregate.class.getName()
        + ") declares it as its 'bpmnProcess'"), startingFirst);
    assertTrue(startingFirst.contains(CallsTheProcess.class.getName()
        + " (workflow aggregate "
        + CustomerAggregate.class.getName()
        + ") lists it in its 'secondaryBpmnProcesses'"),
        startingFirst);
    assertTrue(startingFirst.contains("remove it from 'secondaryBpmnProcesses' of "
        + CallsTheProcess.class.getName()),
        startingFirst);
    assertTrue(startingFirst.contains("mappings of the call activity in your BPMS"), startingFirst);
    assertTrue(startingFirst.contains("messages"), startingFirst);

  }

  @Test
  @DisplayName("Classes of two aggregates both start the process: the start ends, in both orders")
  public void startedByTwoAggregates() {

    final var loanFirst = refusalOfAStartWith(StartsTheProcess.class, AlsoStartsTheProcess.class);
    final var customerFirst = refusalOfAStartWith(AlsoStartsTheProcess.class, StartsTheProcess.class);

    assertEquals(loanFirst, customerFirst, "the order of the classes changed the message");
    assertEquals(
        expectedRefusal(
            asBpmnProcess(StartsTheProcess.class, LoanAggregate.class),
            asBpmnProcess(AlsoStartsTheProcess.class, CustomerAggregate.class)),
        loanFirst);
    assertTrue(loanFirst.contains("Decide which workflow aggregate '%s' belongs to".formatted(SHARED_PROCESS)),
        loanFirst);

  }

  @Test
  @DisplayName("Classes of two aggregates both call the process: the start ends, in both orders")
  public void calledByTwoAggregates() {

    final var loanFirst = refusalOfAStartWith(AlsoCallsTheProcess.class, CallsTheProcess.class);
    final var customerFirst = refusalOfAStartWith(CallsTheProcess.class, AlsoCallsTheProcess.class);

    assertEquals(loanFirst, customerFirst, "the order of the classes changed the message");
    assertEquals(
        expectedRefusal(
            asSecondary(AlsoCallsTheProcess.class, LoanAggregate.class),
            asSecondary(CallsTheProcess.class, CustomerAggregate.class)),
        loanFirst);
    assertTrue(loanFirst.contains("'%s' can be a step of only one workflow".formatted(SHARED_PROCESS)), loanFirst);

  }

  @Test
  @DisplayName("Classes of ONE aggregate sharing a process and a called step still start")
  public void classesOfOneAggregateShareAProcess() {

    try (var context = application(StartsTheProcess.class, SecondHalfOfTheProcess.class, ThirdPartOfTheProcess.class)
        .run()) {

      assertNotNull(context.getBean(ProcessService.class));

    }

  }

}
