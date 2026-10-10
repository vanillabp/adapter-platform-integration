package io.vanillabp.migration.test.workflowtask;

import static io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck.SERVED_BY_NO_METHOD;
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
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * The model a start deploys gets a version from the BPMS, and every task of it needs a
 * <code>&#64;WorkflowTask</code> method whose version range covers that version. The wiring
 * validation runs before the deployment and cannot ask this, so the check of the deployed
 * versions asks it and ends the start where a task is left without a method.
 * <p>
 * The tests drive the registry the way an adapter does: wire the model, report the version
 * the BPMS gave it, hand over the catalog, and let the core resolve the versions of the
 * module.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheDeployedVersionNeedsAMethodTest {

  private static final String MODULE = "loan-approval";

  private static final String PROCESS = "loan_approval";

  private static final String CALLED_PROCESS = "risk_check";

  private static final String ADAPTER = "camunda7";

  private static final String OTHER_ADAPTER = "camunda8";

  private static final BpmnTaskSpec ASSESS_RISK = new BpmnTaskSpec("ServiceTask_AssessRisk", "assessRisk");

  @Getter
  public static class Aggregate {

    String id;

  }

  /**
   * The blueprint after the method for version 1 was removed: only the method for the
   * versions after 1 is left.
   */
  public static class OnlyLaterVersions {

    @WorkflowTask(taskDefinition = "assessRisk", version = ">1")
    public void assessRiskAutomatically(
        final Aggregate aggregate) {
    }

  }

  /**
   * The blueprint as it is: one method for version 1 and one for every later version.
   */
  public static class EveryVersion {

    @WorkflowTask(taskDefinition = "assessRisk", version = "1")
    public void assessRiskByHand(
        final Aggregate aggregate) {
    }

    @WorkflowTask(taskDefinition = "assessRisk", version = ">1")
    public void assessRiskAutomatically(
        final Aggregate aggregate) {
    }

  }

  /**
   * A user task whose only method serves the versions after 1.
   */
  public static class UserTaskOfLaterVersions {

    @WorkflowTask(taskDefinition = "approveForm", version = ">1")
    public void approve(
        final Aggregate aggregate) {
    }

  }

  private MigrationAdapterProperties properties;

  private WorkflowTaskRegistry registry;

  private final Map<String, TaskAdapterProperties> tasks = new LinkedHashMap<>();

  @BeforeEach
  public void setUp() {

    properties = new MigrationAdapterProperties();
    properties
        .setAdapters(Map
            .of(ADAPTER, AdapterConfigProperties.ofType("camunda7"), OTHER_ADAPTER, AdapterConfigProperties
                .ofType("camunda8")));
    final var workflow = new WorkflowAdapterProperties();
    workflow.setTasks(tasks);
    final var module = new WorkflowModuleAdapterProperties();
    module.setWorkflows(Map.of(PROCESS, workflow));
    properties.setWorkflowModules(Map.of(MODULE, module));
    registry = new WorkflowTaskRegistry(new TransactionRunnerStub(), null, List.of(), properties);

  }

  @Test
  @DisplayName("A task whose methods all miss the deployed version ends the start, and the message says how to fix it")
  public void aTaskWhoseMethodsMissTheDeployedVersionEndsTheStart() {

    register(PROCESS, OnlyLaterVersions.class);

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> deploy(ADAPTER, PROCESS, "1", List.of(ASSESS_RISK)));

    final var message = failure.getMessage();
    assertTrue(
        message.startsWith("Version '1' of BPMN process 'loan_approval' (workflow module 'loan-approval') is the "
            + "version adapter 'camunda7' deployed during this start."),
        message);
    assertTrue(message.contains(SERVED_BY_NO_METHOD), message);
    assertTrue(
        message.contains("task 'ServiceTask_AssessRisk' (task definition 'assessRisk')"),
        "the task is named: "
            + message);
    assertTrue(
        message.contains("method '%s#assessRiskAutomatically' (version '>1'), which does not cover version '1'"
            .formatted(OnlyLaterVersions.class.getName())),
        "the method is named with its range: "
            + message);
    assertTrue(message.contains("version = \">=1\""), "the wider range is shown: "
        + message);
    assertTrue(message.contains("add a @WorkflowTask method for version '1'"), message);
    assertTrue(message.contains("deploy the model the method serves"), message);

  }

  @Test
  @DisplayName("A method whose range covers the deployed version is enough")
  public void aMethodCoveringTheDeployedVersionIsEnough() {

    register(PROCESS, OnlyLaterVersions.class);

    assertDoesNotThrow(() -> deploy(ADAPTER, PROCESS, "2", List.of(ASSESS_RISK)));

  }

  @Test
  @DisplayName("Of several methods one covering the deployed version is enough")
  public void oneOfSeveralMethodsIsEnough() {

    register(PROCESS, EveryVersion.class);

    assertDoesNotThrow(() -> deploy(ADAPTER, PROCESS, "1", List.of(ASSESS_RISK)));

  }

  @Test
  @DisplayName("A user task needs a method for the deployed version as well")
  public void aUserTaskNeedsAMethodAsWell() {

    register(PROCESS, UserTaskOfLaterVersions.class);

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> deploy(ADAPTER, PROCESS, "1", List.of(BpmnTaskSpec.userTask("Activity_Approve", "approveForm"))));

    assertTrue(failure.getMessage().contains("task 'Activity_Approve' (task definition 'approveForm')"), failure
        .getMessage());

  }

  @Test
  @DisplayName("A task somebody else serves needs no method, whatever the version")
  public void aTaskServedElsewhereNeedsNoMethod() {

    register(PROCESS, OnlyLaterVersions.class);
    final var external = new TaskAdapterProperties();
    external.setImplementedExternally(true);
    tasks.put("ServiceTask_Notify", external);

    assertDoesNotThrow(() -> deploy(
        ADAPTER,
        PROCESS,
        "2",
        List.of(ASSESS_RISK, new BpmnTaskSpec("ServiceTask_Notify", "notify"))));

  }

  @Test
  @DisplayName("A task somebody else serves for one adapter needs no method on that adapter")
  public void aTaskServedElsewhereForOneAdapterNeedsNoMethodThere() {

    register(PROCESS, OnlyLaterVersions.class);
    final var external = new TaskAdapterProperties();
    final var forOneAdapter = new AdapterProperties();
    forOneAdapter.setImplementedExternally(true);
    external.setAdapters(Map.of(ADAPTER, forOneAdapter));
    tasks.put("ServiceTask_Notify", external);

    assertDoesNotThrow(() -> deploy(
        ADAPTER,
        PROCESS,
        "2",
        List.of(ASSESS_RISK, new BpmnTaskSpec("ServiceTask_Notify", "notify"))));

  }

  @Test
  @DisplayName("Each adapter is held against the version its own BPMS gave the model")
  public void eachAdapterIsHeldAgainstItsOwnVersion() {

    register(PROCESS, OnlyLaterVersions.class);
    registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(ASSESS_RISK));
    registry.validateTaskWiring(OTHER_ADAPTER, MODULE, PROCESS, List.of(ASSESS_RISK));
    registry.registerDeployedVersion(ADAPTER, MODULE, PROCESS, "2");
    registry.registerDeployedVersion(OTHER_ADAPTER, MODULE, PROCESS, "1");
    registry.registerProcessVersions(ADAPTER, MODULE, PROCESS, new CatalogStub("2"));
    registry.registerProcessVersions(OTHER_ADAPTER, MODULE, PROCESS, new CatalogStub("1"));

    final var failure = assertThrows(IllegalStateException.class, () -> registry.resolveProcessVersions(MODULE));

    assertTrue(failure.getMessage().contains("adapter 'camunda8'"), failure.getMessage());
    assertFalse(failure.getMessage().contains("adapter 'camunda7'"), failure.getMessage());

  }

  @Test
  @DisplayName("A called process is held against its own deployed version")
  public void aCalledProcessIsHeldAgainstItsOwnVersion() {

    register(PROCESS, EveryVersion.class);
    register(CALLED_PROCESS, OnlyLaterVersions.class);
    registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(ASSESS_RISK));
    registry.validateTaskWiring(ADAPTER, MODULE, CALLED_PROCESS, List.of(ASSESS_RISK));
    registry.registerDeployedVersion(ADAPTER, MODULE, PROCESS, "3");
    registry.registerDeployedVersion(ADAPTER, MODULE, CALLED_PROCESS, "1");
    registry.registerProcessVersions(ADAPTER, MODULE, PROCESS, new CatalogStub("3"));
    registry.registerProcessVersions(ADAPTER, MODULE, CALLED_PROCESS, new CatalogStub("1"));

    final var failure = assertThrows(IllegalStateException.class, () -> registry.resolveProcessVersions(MODULE));

    assertTrue(failure.getMessage().contains("of BPMN process 'risk_check'"), failure.getMessage());

  }

  @Test
  @DisplayName("A process with no tasks has nothing to serve")
  public void aProcessWithoutTasksIsQuiet() {

    register(PROCESS, OnlyLaterVersions.class);

    // the method then serves no task of the deployed model, which is the other direction and
    // not asked here
    assertDoesNotThrow(() -> deploy(ADAPTER, PROCESS, "1", List.of()));

  }

  @Test
  @DisplayName("A process nobody claims is left alone")
  public void anUnclaimedProcessIsLeftAlone() {

    assertDoesNotThrow(() -> deploy(ADAPTER, "unclaimed", "1", List.of(ASSESS_RISK)));
    assertEquals(Map.of(), registry.tasksTheDeployedVersionLeavesUnserved(ADAPTER, MODULE, "unclaimed", "1"));

  }

  @Test
  @DisplayName("A BPMS which reports no deployed version is not asked")
  public void withoutADeployedVersionNothingIsAsked() {

    register(PROCESS, OnlyLaterVersions.class);
    registry.validateTaskWiring(ADAPTER, MODULE, PROCESS, List.of(ASSESS_RISK));

    assertDoesNotThrow(() -> registry.resolveProcessVersions(MODULE));

  }

  /**
   * What an adapter does for one process: wire the model, report the version its BPMS gave
   * it, hand over the catalog. Then the core resolves the versions of the module, which is
   * where the check runs.
   */
  private void deploy(
      final String adapterId,
      final String bpmnProcessId,
      final String version,
      final List<BpmnTaskSpec> tasksOfTheModel) {

    registry.validateTaskWiring(adapterId, MODULE, bpmnProcessId, tasksOfTheModel);
    registry.registerDeployedVersion(adapterId, MODULE, bpmnProcessId, version);
    registry.registerProcessVersions(adapterId, MODULE, bpmnProcessId, new CatalogStub(version));
    registry.resolveProcessVersions(MODULE);

  }

  private void register(
      final String bpmnProcessId,
      final Class<?> workflowServiceClass) {

    registry
        .registerWorkflowService(
            MODULE,
            bpmnProcessId,
            workflowServiceClass,
            () -> null,
            type -> null,
            processService());

  }

  /**
   * A BPMS holding nothing but the version this start deployed, so no older version adds a
   * finding of its own.
   */
  private record CatalogStub(
                             String deployed) implements ProcessVersionCatalog {

    @Override
    public List<DeployedProcessVersion> deployedVersionsOf(
        final String workflowModuleId,
        final String bpmnProcessId) {

      return List.of(DeployedProcessVersion.of(deployed));

    }

    @Override
    public DeployedProcessVersion resolveVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String versionOrVersionTag) {

      return null;

    }

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
