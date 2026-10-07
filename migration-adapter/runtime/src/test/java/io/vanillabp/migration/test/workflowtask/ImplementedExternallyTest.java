package io.vanillabp.migration.test.workflowtask;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.AdapterProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.TaskAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowAdapterProperties;
import io.vanillabp.integration.adapter.migration.config.WorkflowModuleAdapterProperties;
import io.vanillabp.integration.adapter.migration.startup.StartupFindings;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.ImplementedExternally;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * Every task of a claimed BPMN process needs a <code>&#64;WorkflowTask</code> method or the
 * property <code>implemented-externally=true</code>, a user task and a listener as well. The
 * property names a task by its element id or by its task definition, the element id winning
 * where both are written, and it may be written for one adapter only.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ImplementedExternallyTest {

  private static final String MODULE = "loans";

  private static final String PROCESS = "LoanApproval";

  private static final String ADAPTER = "c8";

  private static final String OTHER_ADAPTER = "c7";

  @Getter
  public static class Aggregate {

    String id;

  }

  /**
   * Serves the service task 'score' and nothing else of the model.
   */
  @WorkflowService(
      workflowAggregateClass = Aggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class LoanService {

    @WorkflowTask(taskDefinition = "score")
    public void score(
        final Aggregate aggregate) {
    }

    @WorkflowTask(id = "Activity_Approve")
    public void approveByElement(
        final Aggregate aggregate) {
    }

  }

  private static final BpmnTaskSpec SCORE = new BpmnTaskSpec("Activity_Score", "score");

  private static final BpmnTaskSpec APPROVE = BpmnTaskSpec.userTask("Activity_Approve", "approveForm");

  private static final BpmnTaskSpec REVIEW = BpmnTaskSpec.userTask("Activity_Review", "reviewForm");

  private MigrationAdapterProperties properties;

  private WorkflowTaskRegistry registry;

  private final Map<String, TaskAdapterProperties> tasks = new LinkedHashMap<>();

  private WorkflowAdapterProperties workflow;

  private WorkflowModuleAdapterProperties module;

  @BeforeEach
  public void setUp() {

    properties = new MigrationAdapterProperties();
    properties
        .setAdapters(Map
            .of(ADAPTER, AdapterConfigProperties.ofType("camunda8"), OTHER_ADAPTER, AdapterConfigProperties
                .ofType("camunda7")));
    workflow = new WorkflowAdapterProperties();
    workflow.setTasks(tasks);
    module = new WorkflowModuleAdapterProperties();
    module.setWorkflows(Map.of(PROCESS, workflow));
    properties.setWorkflowModules(Map.of(MODULE, module));
    registry = new WorkflowTaskRegistry(new TransactionRunnerStub(), null, List.of(), properties);
    registry
        .registerWorkflowService(MODULE, PROCESS, LoanService.class, () -> null, type -> null, processService());

  }

  @Test
  @DisplayName("A user task without a method ends the start, and the message hands over the line")
  public void aUserTaskWithoutAMethodEndsTheStart() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW)));

    final var message = findingsOf(exception);
    assertTrue(message.contains("task 'Activity_Review' (task definition 'reviewForm')"), message);
    assertTrue(
        message.contains("vanillabp.workflow-modules.loans.workflows.LoanApproval.tasks.Activity_Review."
            + "implemented-externally=true"),
        message);
    assertFalse(message.contains("Activity_Approve"), "a method serves the other user task: "
        + message);

  }

  @Test
  @DisplayName("A user task is marked by its element id or by its task definition")
  public void aUserTaskIsMarkedByEitherName() {

    tasks.put("Activity_Review", marked(true));
    assertDoesNotThrow(() -> registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW)));

    tasks.clear();
    tasks.put("reviewForm", marked(true));
    assertDoesNotThrow(() -> registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW)));

  }

  @Test
  @DisplayName("Where both names are written, the element id wins")
  public void theElementIdWins() {

    tasks.put("Activity_Review", marked(false));
    tasks.put("reviewForm", marked(true));

    assertThrows(
        IllegalStateException.class,
        () -> registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW)));
    assertFalse(registry.isImplementedExternally(ADAPTER, MODULE, PROCESS, REVIEW));

  }

  @Test
  @DisplayName("A line for one adapter marks the task for that adapter only")
  public void aLineForOneAdapterMarksOnlyThatOne() {

    final var task = new TaskAdapterProperties();
    final var forOneAdapter = new AdapterProperties();
    forOneAdapter.setImplementedExternally(true);
    task.setAdapters(Map.of(ADAPTER, forOneAdapter));
    tasks.put("Activity_Review", task);

    assertDoesNotThrow(() -> registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW)));
    assertThrows(
        IllegalStateException.class,
        () -> registry.validateTaskWiring(OTHER_ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW)));

  }

  @Test
  @DisplayName("What an adapter is told beats what the same level says, and a task beats its workflow")
  public void theMostSpecificPositionWins() {

    // the workflow says every task is served elsewhere, the task says it is not
    workflow.setImplementedExternally(true);
    assertTrue(registry.isImplementedExternally(ADAPTER, MODULE, PROCESS, REVIEW));
    tasks.put("reviewForm", marked(false));
    assertFalse(registry.isImplementedExternally(ADAPTER, MODULE, PROCESS, REVIEW));

    // and the task, for one adapter, says it is after all
    final var forOneAdapter = new AdapterProperties();
    forOneAdapter.setImplementedExternally(true);
    tasks.get("reviewForm").setAdapters(Map.of(ADAPTER, forOneAdapter));
    assertTrue(registry.isImplementedExternally(ADAPTER, MODULE, PROCESS, REVIEW));
    assertFalse(registry.isImplementedExternally(OTHER_ADAPTER, MODULE, PROCESS, REVIEW));

    // the least specific positions are read as well
    tasks.clear();
    workflow.setImplementedExternally(null);
    properties.setImplementedExternally(true);
    assertTrue(registry.isImplementedExternally(ADAPTER, MODULE, PROCESS, REVIEW));
    module.setImplementedExternally(false);
    assertFalse(registry.isImplementedExternally(ADAPTER, MODULE, PROCESS, REVIEW));
    properties.getAdapters().get(ADAPTER).setImplementedExternally(true);
    assertFalse(
        registry.isImplementedExternally(ADAPTER, MODULE, PROCESS, REVIEW),
        "the workflow module is more specific than the adapter section of the application");
    assertEquals(
        Boolean.TRUE,
        properties.implementedExternally("other-module", PROCESS, List.of("x"), null),
        "a module which says nothing gets what the application says");

  }

  @Test
  @DisplayName("A method next to a line at the task ends the start, because both would answer the task")
  public void aMethodAndTheLineEndTheStart() {

    tasks.put("Activity_Review", marked(true));
    tasks.put("score", marked(true));

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW)));

    final var message = findingsOf(exception);
    assertTrue(message.contains("task 'Activity_Score' (task definition 'score')"), message);
    assertTrue(message.contains("remove the method or the line"), message);
    assertFalse(message.contains("task 'Activity_Review'"), message);

  }

  @Test
  @DisplayName("A line above the task covers a task without a method, and a method next to it wins without a word")
  public void aLineAboveTheTaskCoversOnlyTasksWithoutAMethod() {

    // the workflow says that something else serves its tasks: 'score' and 'Activity_Approve'
    // have methods and keep them, 'Activity_Review' has none and is covered
    workflow.setImplementedExternally(true);
    assertDoesNotThrow(() -> registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW)));

    // the same at the workflow module and at the application, for one adapter
    workflow.setImplementedExternally(null);
    final var forOneAdapter = new AdapterProperties();
    forOneAdapter.setImplementedExternally(true);
    module.setAdapters(Map.of(ADAPTER, forOneAdapter));
    assertDoesNotThrow(() -> registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW)));
    assertThrows(
        IllegalStateException.class,
        () -> registry.validateTaskWiring(OTHER_ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW)),
        "the other adapter was told nothing");

    module.setAdapters(Map.of());
    properties.setImplementedExternally(true);
    assertDoesNotThrow(() -> registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW)));

  }

  @Test
  @DisplayName("A line at the task says false where the line above says true, and the task needs its method again")
  public void aLineAtTheTaskTakesTheTaskBack() {

    workflow.setImplementedExternally(true);
    tasks.put("Activity_Review", marked(false));

    assertThrows(
        IllegalStateException.class,
        () -> registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW)));

  }

  @Test
  @DisplayName("A line above the task which covers no task without a method is a warning")
  public void aLineAboveTheTaskNothingNeedsIsAWarning() {

    workflow.setImplementedExternally(true);
    module.setImplementedExternally(true);

    registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE));
    registry.validateNoUnwiredWorkflowTaskMethods(MODULE);

    final var warnings = warnings();
    assertEquals(2, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("for BPMN process 'LoanApproval'"), warnings.get(0));
    assertTrue(warnings.get(1).contains("for workflow module 'loans'"), warnings.get(1));

  }

  @Test
  @DisplayName("A line above the task which covers one task without a method is no warning")
  public void aLineAboveTheTaskSomethingNeedsIsSilent() {

    workflow.setImplementedExternally(true);

    registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW));
    registry.validateNoUnwiredWorkflowTaskMethods(MODULE);

    assertEquals(List.of(), warnings());

  }

  private List<String> warnings() {

    return properties
        .startupFindings()
        .findings()
        .stream()
        .filter(finding -> finding.severity() == StartupFindings.Severity.WARNING)
        .map(StartupFindings.Finding::message)
        .toList();

  }

  @Test
  @DisplayName("A listener is served by its task definition only, and its element id marks it")
  public void aListenerIsServedByItsTaskDefinitionOnly() {

    // the method naming the element of the user task does not serve a listener on it
    final var listener = BpmnTaskSpec.listener("Activity_Approve", "io.camunda:audit:1", null);
    final var exception = assertThrows(
        IllegalStateException.class,
        () -> registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, listener)));

    final var message = exception.getMessage();
    assertTrue(message.contains("listener on 'Activity_Approve' (task definition 'io.camunda:audit:1')"), message);
    // the job type carries a colon and dots, so the line is protected per platform
    assertTrue(
        message.contains("# Spring Boot\n") && message
            .contains("tasks[io.camunda\\:audit\\:1].implemented-externally=true\n"),
        message);
    assertTrue(
        message.contains("# Quarkus\n") && message
            .contains("tasks.\"io.camunda\\:audit\\:1\".implemented-externally=true"),
        message);
    assertTrue(message.contains("@WorkflowTask(taskDefinition = \"io.camunda:audit:1\")"), message);
    assertFalse(message.contains("@WorkflowTask(id = \"Activity_Approve\")"), message);

    tasks.put("io.camunda:audit:1", marked(true));
    assertDoesNotThrow(() -> registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, listener)));

  }

  @Test
  @DisplayName("The element id of a user task covers every listener on it")
  public void theElementIdCoversItsListeners() {

    tasks.put("Activity_Review", marked(true));
    final var creating = BpmnTaskSpec.listener("Activity_Review", "audit-creating", null);
    final var completing = BpmnTaskSpec.listener("Activity_Review", "audit-completing", null);

    assertDoesNotThrow(
        () -> registry
            .validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW, creating, completing)));

  }

  @Test
  @DisplayName("A line for a task no deployed model has is a warning once the module is deployed")
  public void aLineNoModelNeedsIsAWarning() {

    tasks.put("Activity_Review", marked(true));
    tasks.put("Activity_Gone", marked(true));
    final var elsewhere = new WorkflowAdapterProperties();
    final var task = new TaskAdapterProperties();
    task.setImplementedExternally(true);
    elsewhere.setTasks(Map.of("anything", task));
    module.setWorkflows(Map.of(PROCESS, workflow, "NotDeployed", elsewhere));

    registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE, REVIEW));
    registry.validateNoUnwiredWorkflowTaskMethods(MODULE);

    final var warnings = properties
        .startupFindings()
        .findings()
        .stream()
        .filter(finding -> finding.severity() == StartupFindings.Severity.WARNING)
        .map(StartupFindings.Finding::message)
        .toList();
    assertEquals(2, warnings.size(), warnings.toString());
    assertTrue(warnings.get(0).contains("'Activity_Gone'"), warnings.get(0));
    assertFalse(warnings.get(0).contains("'Activity_Review'"), warnings.get(0));
    assertTrue(warnings.get(1).contains("this start deployed no BPMN process 'NotDeployed'"), warnings.get(1));

  }

  @Test
  @DisplayName("A task only the model of the second adapter has is not a line nothing needs")
  public void theModelsOfAllAdaptersCount() {

    tasks.put("Activity_New", marked(true));

    registry.validateTaskWiring(OTHER_ADAPTER, MODULE, PROCESS, List.of(SCORE, APPROVE));
    registry.validateTaskWiring(ADAPTER, MODULE, PROCESS,
        List.of(SCORE, APPROVE, new BpmnTaskSpec("Activity_New", "newTask")));
    registry.validateNoUnwiredWorkflowTaskMethods(MODULE);

    assertTrue(
        properties
            .startupFindings()
            .findings()
            .isEmpty(),
        () -> properties.startupFindings().findings().toString());

  }

  @Test
  @DisplayName("A name which needs no protection is one line")
  public void aPlainNameIsOneLine() {

    assertEquals(
        "vanillabp.workflow-modules.loans.workflows.LoanApproval.tasks.Activity_1.implemented-externally=true",
        ImplementedExternally.propertyLine(MODULE, PROCESS, "Activity_1"));

  }

  /**
   * What the message says about the tasks it ends the start over, without the list of every
   * task and method it closes with.
   */
  private static String findingsOf(
      final IllegalStateException exception) {

    final var message = exception.getMessage();
    return message.substring(0, message.indexOf("\nTasks of the BPMN process"));

  }

  private static TaskAdapterProperties marked(
      final Boolean value) {

    final var task = new TaskAdapterProperties();
    task.setImplementedExternally(value);
    return task;

  }

  @SuppressWarnings({
      "unchecked", "rawtypes"
  })
  private static io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService<Aggregate> processService() {

    final var processService = org.mockito.Mockito
        .mock(io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService.class);
    org.mockito.Mockito
        .when(processService.getWorkflowAggregateClass())
        .thenReturn((Class) Aggregate.class);
    return processService;

  }

  /**
   * The transaction runner is irrelevant here - no test in this class runs a handler.
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
