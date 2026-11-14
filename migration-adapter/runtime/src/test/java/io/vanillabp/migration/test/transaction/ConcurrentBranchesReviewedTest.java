package io.vanillabp.migration.test.transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.CompensationSpec;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * The hint about two branches which write one workflow aggregate without a version attribute.
 * It names ways out which VanillaBP cannot see in the code, <code>&#64;DynamicUpdate</code>
 * among them. A developer who took one of them reviews the branches and says so for the
 * workflow. From then on the hint is silent for that workflow, and only for that one.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ConcurrentBranchesReviewedTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "LoanApproval";

  private static final String OTHER_PROCESS = "LoanRepayment";

  private static final String ABOUT_TWO_TOKENS = "hold more than one token";

  @Getter
  public static class Aggregate {

    String id;

  }

  public static class Service {

    @WorkflowTask(taskDefinition = "checkCredit")
    public void checkCredit(
        final Aggregate aggregate) {
    }

  }

  @Test
  @DisplayName("Without a review, the hint guides to the key which ends it, and says that @DynamicUpdate alone does not")
  public void theHintNamesTheKeyWhichEndsIt() {

    final var properties = propertiesReviewing(null);

    final var entries = whatTheCheckSaidAbout(properties, PROCESS);

    assertEquals(1, entries.size(), entries.toString());
    final var entry = entries.getFirst();
    assertTrue(entry.contains("process '%s' of workflow module '%s'".formatted(PROCESS, MODULE)), entry);
    assertTrue(entry.contains("@DynamicUpdate where the branches write different attributes"), entry);
    assertTrue(entry.contains("VanillaBP cannot see them in your code, so this hint stays"), entry);
    assertTrue(
        entry
            .contains(
                "'vanillabp.workflow-modules.<workflow-module>.workflows.<bpmn-process-id>"
                    + ".concurrent-branches-reviewed: true'"),
        entry);

  }

  @Test
  @DisplayName("A workflow whose branches were reviewed gets no hint, for a gateway as well as for compensation")
  public void aReviewedWorkflowGetsNoHint() {

    final var properties = propertiesReviewing(PROCESS);

    final var registry = registryServing(properties, PROCESS);
    registry.reportConcurrentTokenElements(MODULE, PROCESS, List.of("Gateway_Fork"));
    registry
        .reportCompensation(
            MODULE, PROCESS, List.of(new CompensationSpec("UndoEverything", List.of("CancelA", "CancelB"))));

    assertEquals(List.of(), entriesAboutTwoTokens(properties));

  }

  @Test
  @DisplayName("The review of one workflow does not silence another workflow of the same module")
  public void theReviewCoversOnlyItsOwnWorkflow() {

    final var properties = propertiesReviewing(PROCESS);

    final var entries = whatTheCheckSaidAbout(properties, OTHER_PROCESS);

    assertEquals(1, entries.size(), entries.toString());
    assertTrue(entries.getFirst().contains("process '%s'".formatted(OTHER_PROCESS)), entries.toString());

  }

  @Test
  @DisplayName("A review written as false keeps the hint")
  public void aReviewWrittenAsFalseKeepsTheHint() {

    final var workflow = WorkflowAdapterProperties
        .builder()
        .bpmnProcessId(PROCESS)
        .concurrentBranchesReviewed(Boolean.FALSE)
        .build();
    final var properties = propertiesWith(Map.of(PROCESS, workflow));

    assertEquals(1, whatTheCheckSaidAbout(properties, PROCESS).size());

  }

  @Test
  @DisplayName("The key is resolved at the workflow, and the hint names its pattern")
  public void theKeyIsResolvedAtTheWorkflow() {

    final var properties = propertiesReviewing(PROCESS);

    assertTrue(properties.concurrentBranchesReviewed(MODULE, PROCESS));
    assertEquals(false, properties.concurrentBranchesReviewed(MODULE, OTHER_PROCESS));
    assertEquals(false, properties.concurrentBranchesReviewed("unknown-module", PROCESS));
    assertEquals(false, properties.concurrentBranchesReviewed(null, null));
    assertEquals(
        "vanillabp.workflow-modules.loan-approval.workflows.LoanApproval.concurrent-branches-reviewed",
        MigrationAdapterProperties.concurrentBranchesReviewedProperty(MODULE, PROCESS));

  }

  private static MigrationAdapterProperties propertiesReviewing(
      final String reviewedProcess) {

    final var workflows = reviewedProcess == null
        ? Map.<String, WorkflowAdapterProperties>of()
        : Map
            .of(
                reviewedProcess,
                WorkflowAdapterProperties
                    .builder()
                    .bpmnProcessId(reviewedProcess)
                    .concurrentBranchesReviewed(Boolean.TRUE)
                    .build());
    return propertiesWith(workflows);

  }

  private static MigrationAdapterProperties propertiesWith(
      final Map<String, WorkflowAdapterProperties> workflows) {

    final var properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of("c7", AdapterConfigProperties.ofType("camunda7")));
    properties
        .setWorkflowModules(
            Map
                .of(
                    MODULE,
                    WorkflowModuleAdapterProperties
                        .builder()
                        .workflowModuleId(MODULE)
                        .workflows(workflows)
                        .build()));
    return properties;

  }

  /**
   * What the check said while an adapter wired the given process with a forking gateway.
   */
  private static List<String> whatTheCheckSaidAbout(
      final MigrationAdapterProperties properties,
      final String bpmnProcessId) {

    registryServing(properties, bpmnProcessId)
        .reportConcurrentTokenElements(MODULE, bpmnProcessId, List.of("Gateway_Fork"));
    return entriesAboutTwoTokens(properties);

  }

  private static List<String> entriesAboutTwoTokens(
      final MigrationAdapterProperties properties) {

    return io.vanillabp.migration.test.startup.WhatWasFound
        .entries(properties.startupFindings())
        .stream()
        .filter(entry -> entry.contains(ABOUT_TWO_TOKENS))
        .toList();

  }

  private static WorkflowTaskRegistry registryServing(
      final MigrationAdapterProperties properties,
      final String bpmnProcessId) {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub(), null, List.of(), properties);
    registry
        .registerWorkflowService(
            MODULE,
            bpmnProcessId,
            Service.class,
            () -> null,
            type -> null,
            processService());
    registry.validateTaskWiring(MODULE, bpmnProcessId, List.of(new BpmnTaskSpec("Task_Check", "checkCredit")));
    return registry;

  }

  /**
   * The registry needs a process service to register a workflow service. Nothing here invokes
   * it. Its aggregate class, which has no version attribute, is what the check asks about.
   */
  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  private static io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService<?> processService() {

    final var processService = org.mockito.Mockito
        .mock(io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService.class);
    org.mockito.Mockito
        .when(processService.getWorkflowAggregateClass())
        .thenReturn((Class) Aggregate.class);
    org.mockito.Mockito
        .when(processService.detectsConcurrentModification())
        .thenReturn(false);
    return processService;

  }

  /**
   * The transaction runner does not matter here, because no test in this class runs a
   * handler.
   */
  private static class TransactionRunnerStub implements TransactionRunner {

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

  }

}
