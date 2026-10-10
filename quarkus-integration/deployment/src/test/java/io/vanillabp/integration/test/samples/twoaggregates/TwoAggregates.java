package io.vanillabp.integration.test.samples.twoaggregates;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;

import io.vanillabp.integration.adapter.migration.workflowtask.AProcessBelongsToOneAggregate;
import io.vanillabp.integration.adapter.migration.workflowtask.AProcessBelongsToOneAggregate.Declaration;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.inject.Singleton;

/**
 * Workflow services of two aggregates, for the tests which hold the rule that a BPMN process
 * belongs to exactly one workflow aggregate. Each test hands the classes of its case to its own
 * application, in the order it wants them found.
 */
public final class TwoAggregates {

  public static final String MODULE = "test-module";

  public static final String SHARED_PROCESS = "RiskAssessment";

  private TwoAggregates() {
  }

  public static class LoanAggregate {
  }

  public static class CustomerAggregate {
  }

  @Singleton
  @WorkflowService(
      workflowAggregateClass = LoanAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = SHARED_PROCESS))
  public static class StartsTheProcess {
  }

  @Singleton
  @WorkflowService(
      workflowAggregateClass = CustomerAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "CustomerOnboarding"),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = SHARED_PROCESS))
  public static class CallsTheProcess {
  }

  @Singleton
  @WorkflowService(
      workflowAggregateClass = CustomerAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = SHARED_PROCESS))
  public static class AlsoStartsTheProcess {
  }

  @Singleton
  @WorkflowService(
      workflowAggregateClass = LoanAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "LoanApproval"),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = SHARED_PROCESS))
  public static class AlsoCallsTheProcess {
  }

  /**
   * Shares its process and its called step with {@link AlsoCallsTheProcess}, on the same
   * aggregate, which stays allowed.
   */
  @Singleton
  @WorkflowService(
      workflowAggregateClass = LoanAggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "LoanApproval"),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = SHARED_PROCESS))
  public static class SecondHalfOfTheProcess {
  }

  @Singleton
  public static class LoanAggregatePersistence implements AggregatePersistenceAware<LoanAggregate> {

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
      return null;
    }

  }

  public static Declaration asBpmnProcess(
      final Class<?> workflowService,
      final Class<?> aggregate) {

    return new Declaration(MODULE, SHARED_PROCESS, workflowService.getName(), aggregate.getName(), true);

  }

  public static Declaration asSecondary(
      final Class<?> workflowService,
      final Class<?> aggregate) {

    return new Declaration(MODULE, SHARED_PROCESS, workflowService.getName(), aggregate.getName(), false);

  }

  /**
   * Holds the exception which ended the build against the text the core builds for the
   * declarations given, which is the text Spring Boot says as well.
   *
   * @param throwable What ended the build
   * @param declarations The declarations the message has to report
   */
  public static void assertTheBuildWasRefused(
      final Throwable throwable,
      final Declaration... declarations) {

    final var expected = AProcessBelongsToOneAggregate
        .refusalOf(List.of(declarations))
        .orElseThrow();
    var current = throwable;
    while (current != null) {
      if ((current.getMessage() != null) && current.getMessage()
          .startsWith("The BPMN process '%s'".formatted(SHARED_PROCESS))) {
        assertEquals(expected, current.getMessage());
        return;
      }
      current = current.getCause();
    }
    fail("expected the build to refuse a process of two aggregates but got: "
        + throwable);

  }

}
