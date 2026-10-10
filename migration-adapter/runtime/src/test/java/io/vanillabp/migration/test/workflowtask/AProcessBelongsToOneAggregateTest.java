package io.vanillabp.migration.test.workflowtask;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.workflowtask.AProcessBelongsToOneAggregate;
import io.vanillabp.integration.adapter.migration.workflowtask.AProcessBelongsToOneAggregate.Declaration;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * A BPMN process belongs to exactly one workflow aggregate. The text of the refusal is built
 * here for both platforms, and the registry of task handlers says it as well, for a platform
 * which did not check before. Both platforms check before: their own tests are
 * {@code AProcessBelongsToOneAggregateTest} of the Spring Boot main integration test and the
 * {@code AProcessOfTwoAggregates*Test} classes of the Quarkus deployment module.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AProcessBelongsToOneAggregateTest {

  private static final String MODULE = "loans";

  private static final String PROCESS = "RiskAssessment";

  private static final String ADAPTER = "test-adapter";

  public static class Loan {

    String id;

  }

  public static class Customer {

    String id;

  }

  @WorkflowService(
      workflowAggregateClass = Loan.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class StartsTheProcess {
  }

  @WorkflowService(
      workflowAggregateClass = Customer.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "CustomerOnboarding"),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class CallsTheProcess {
  }

  @WorkflowService(
      workflowAggregateClass = Loan.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = "LoanApproval"),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class StepOfTheLoanApproval {
  }

  private static Declaration startedBy(
      final Class<?> workflowService,
      final Class<?> aggregate) {

    return new Declaration(MODULE, PROCESS, workflowService.getName(), aggregate.getName(), true);

  }

  private static Declaration calledBy(
      final Class<?> workflowService,
      final Class<?> aggregate) {

    return new Declaration(MODULE, PROCESS, workflowService.getName(), aggregate.getName(), false);

  }

  @Test
  @DisplayName("One aggregate starts the process and another calls it: both classes, both aggregates, the advice")
  public void startedByOneAggregateAndCalledByAnother() {

    final var message = AProcessBelongsToOneAggregate
        .refusalOf(List.of(
            startedBy(StartsTheProcess.class, Loan.class),
            calledBy(CallsTheProcess.class, Customer.class)))
        .orElseThrow();

    assertEquals("""
        The BPMN process '%1$s' of workflow module '%2$s' is declared for 2 workflow aggregates:
          %3$s (workflow aggregate %4$s) declares it as its 'bpmnProcess'
          %5$s (workflow aggregate %6$s) lists it in its 'secondaryBpmnProcesses'
        A BPMN process belongs to exactly one workflow aggregate. Otherwise the order in which the \
        classes are found decides which aggregate the tasks of '%1$s' load, and whether '%1$s' is a \
        workflow of its own or a step of another workflow. So the start stops here.
        Decide what '%1$s' is:
        - If '%1$s' is a workflow of its own, with the workflow aggregate %4$s, remove it from \
        'secondaryBpmnProcesses' of %5$s. A call activity may still call '%1$s'. But then the call is \
        a matter of your BPMS. The data the called workflow gets are set up by the mappings of the \
        call activity in your BPMS, not by VanillaBP. Two separate workflows are usually better \
        started and synchronised by messages. Then the contract between them lives in your business \
        code and not in the BPMN.
        - If '%1$s' is a step of the workflow of %5$s, only that class lists it in its \
        'secondaryBpmnProcesses'. Then %3$s must not declare it. Give that class a 'bpmnProcess' of its \
        own, or move its @WorkflowTask methods for '%1$s' into a class of the other workflow aggregate."""
        .formatted(
            PROCESS,
            MODULE,
            StartsTheProcess.class.getName(),
            Loan.class.getName(),
            CallsTheProcess.class.getName(),
            Customer.class.getName()),
        message);
    assertFalse(message.contains("process variables"),
        "not every BPMS has process variables, so the advice must not name them: "
            + message);

  }

  @Test
  @DisplayName("Two aggregates start the process: the advice is to pick one aggregate")
  public void startedByTwoAggregates() {

    final var message = AProcessBelongsToOneAggregate
        .refusalOf(List.of(
            startedBy(StartsTheProcess.class, Loan.class),
            startedBy(CallsTheProcess.class, Customer.class)))
        .orElseThrow();

    assertTrue(message.contains("Decide which workflow aggregate '%s' belongs to".formatted(PROCESS)), message);
    assertTrue(message.contains("mappings of the call activity in your BPMS"), message);
    assertTrue(message.contains("only the class of that workflow lists it"), message);

  }

  @Test
  @DisplayName("Two aggregates call the process: the advice is to keep it a step of one of them")
  public void calledByTwoAggregates() {

    final var message = AProcessBelongsToOneAggregate
        .refusalOf(List.of(
            calledBy(StepOfTheLoanApproval.class, Loan.class),
            calledBy(CallsTheProcess.class, Customer.class)))
        .orElseThrow();

    assertTrue(message.contains("'%s' can be a step of only one workflow".formatted(PROCESS)), message);
    assertTrue(message.contains("a copy of the process with a BPMN process ID of its own"), message);
    assertTrue(message.contains("mappings of the call activity in your BPMS"), message);

  }

  @Test
  @DisplayName("Classes of one aggregate may share a process, however they declare it")
  public void oneAggregateIsNoConflict() {

    assertTrue(AProcessBelongsToOneAggregate
        .refusalOf(List.of(
            startedBy(StartsTheProcess.class, Loan.class),
            calledBy(StepOfTheLoanApproval.class, Loan.class)))
        .isEmpty());
    assertDoesNotThrow(() -> AProcessBelongsToOneAggregate.refuseProcessesOfSeveralAggregates(List.of(
        startedBy(StartsTheProcess.class, Loan.class),
        new Declaration("another-module", PROCESS, CallsTheProcess.class.getName(), Customer.class.getName(), true))),
        "the same id in another workflow module is another process");

  }

  @Test
  @DisplayName("The message does not depend on the order the classes are found in")
  public void theOrderDoesNotMatter() {

    assertEquals(
        AProcessBelongsToOneAggregate.refusalOf(List.of(
            startedBy(StartsTheProcess.class, Loan.class),
            calledBy(CallsTheProcess.class, Customer.class))),
        AProcessBelongsToOneAggregate.refusalOf(List.of(
            calledBy(CallsTheProcess.class, Customer.class),
            startedBy(StartsTheProcess.class, Loan.class))));

  }

  @Test
  @DisplayName("The registry of task handlers refuses the second aggregate instead of keeping the first, in both orders")
  public void theRegistryRefusesInBothOrders() {

    final var expected = AProcessBelongsToOneAggregate
        .refusalOf(List.of(
            startedBy(StartsTheProcess.class, Loan.class),
            calledBy(CallsTheProcess.class, Customer.class)))
        .orElseThrow();

    final var startingFirst = new WorkflowTaskRegistry(mock(TransactionRunner.class));
    startingFirst.registerWorkflowService(
        MODULE, PROCESS, StartsTheProcess.class, StartsTheProcess::new, type -> null, processService(Loan.class));
    final var refusedAfterTheStarter = assertThrows(
        IllegalStateException.class,
        () -> startingFirst.registerWorkflowService(
            MODULE, PROCESS, CallsTheProcess.class, CallsTheProcess::new, type -> null,
            processService(Customer.class)));
    assertEquals(expected, refusedAfterTheStarter.getMessage());

    final var callingFirst = new WorkflowTaskRegistry(mock(TransactionRunner.class));
    callingFirst.registerWorkflowService(
        MODULE, PROCESS, CallsTheProcess.class, CallsTheProcess::new, type -> null, processService(Customer.class));
    final var refusedAfterTheCaller = assertThrows(
        IllegalStateException.class,
        () -> callingFirst.registerWorkflowService(
            MODULE, PROCESS, StartsTheProcess.class, StartsTheProcess::new, type -> null, processService(Loan.class)));
    assertEquals(expected, refusedAfterTheCaller.getMessage());

  }

  @Test
  @DisplayName("The registry still merges classes of one aggregate")
  public void theRegistryMergesOneAggregate() {

    final var registry = new WorkflowTaskRegistry(mock(TransactionRunner.class));
    registry.registerWorkflowService(
        MODULE, PROCESS, StartsTheProcess.class, StartsTheProcess::new, type -> null, processService(Loan.class));
    assertDoesNotThrow(() -> registry.registerWorkflowService(
        MODULE, PROCESS, StepOfTheLoanApproval.class, StepOfTheLoanApproval::new, type -> null,
        processService(Loan.class)));

  }

  private static <A> MigrationProcessService<A> processService(
      final Class<A> aggregateClass) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();

    @SuppressWarnings("unchecked")
    final MigratableProcessService<A> adapter = mock(MigratableProcessService.class);
    lenient()
        .when(adapter.getAdapterId())
        .thenReturn(ADAPTER);

    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, aggregateClass)
        .properties(properties)
        .aggregatePersistence(new AggregatePersistenceAware<A>() {

          @Override
          public Class<A> getAggregateClass() {
            return aggregateClass;
          }

          @Override
          public String getAggregateIdName() {
            return "id";
          }

        })
        .processServices(List.of(adapter))
        .build();

  }

}
