package io.vanillabp.migration.test.workflowtask;

import static io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck.A_TASK_WITHOUT_A_METHOD_STOPS_EVERY_WORKFLOW;
import static io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck.ELEMENTS_SERVED_BY_NO_METHOD_IN_THIS_VERSION;
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
 * The BPMS gives the model which a start deploys a version. Every task of that model needs
 * a <code>&#64;WorkflowTask</code> method whose version range covers that version. The
 * wiring check runs before the deployment and cannot ask this. So the check of the deployed
 * versions asks it, and it ends the start where a task is left without a method.
 * <p>
 * The tests drive the registry the way an adapter does. They wire the model, report the
 * version the BPMS gave it, hand over the catalog, and let the core resolve the versions of
 * the module.
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
   * The blueprint after the method for version 1 was removed. Only the method for the
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
   * The annotation of a test extension whose contract asks for a method for the deployed
   * version.
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
  public @interface Note {

    String element() default "";

    String version() default "";

  }

  /**
   * The same, for a contract which does not ask for it.
   */
  @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
  @java.lang.annotation.Target(java.lang.annotation.ElementType.METHOD)
  public @interface Remark {

    String element() default "";

    String version() default "";

  }

  /**
   * Every task is served in every version. The notes of the extension serve only the
   * versions after 1: one note for the task, and one for the process itself.
   */
  public static class NotesOfLaterVersions {

    @WorkflowTask(taskDefinition = "assessRisk")
    public void assessRisk(
        final Aggregate aggregate) {
    }

    @Note(element = "ServiceTask_AssessRisk", version = ">1")
    public void noteAboutTheRisk(
        final Aggregate aggregate) {
    }

    @Note(element = io.vanillabp.integration.extension.spi.handler.HandlerContract.EVERY_KEY, version = ">1")
    public void noteAboutTheWorkflow(
        final Aggregate aggregate) {
    }

    @Remark(element = "ServiceTask_AssessRisk", version = ">1")
    public void remarkAboutTheRisk(
        final Aggregate aggregate) {
    }

  }

  /**
   * The note for the task covers every version, and nothing serves the process itself.
   */
  public static class NotesOfEveryVersion {

    @WorkflowTask(taskDefinition = "assessRisk")
    public void assessRisk(
        final Aggregate aggregate) {
    }

    @Note(element = "ServiceTask_AssessRisk", version = "1")
    public void noteOfTheFirstVersion(
        final Aggregate aggregate) {
    }

    @Note(element = "ServiceTask_AssessRisk", version = ">1")
    public void noteOfLaterVersions(
        final Aggregate aggregate) {
    }

  }

  /**
   * Notes of the versions after 1, next to a task whose method also misses version 1. So
   * one message names both.
   */
  public static class TaskAndNoteOfLaterVersions {

    @WorkflowTask(taskDefinition = "assessRisk", version = ">1")
    public void assessRisk(
        final Aggregate aggregate) {
    }

    @Note(element = "ServiceTask_AssessRisk", version = ">1")
    public void noteAboutTheRisk(
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
    assertTrue(message.contains(ELEMENTS_SERVED_BY_NO_METHOD_IN_THIS_VERSION), message);
    assertTrue(message.contains(A_TASK_WITHOUT_A_METHOD_STOPS_EVERY_WORKFLOW), "a task keeps its incident sentence: "
        + message);
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
    assertTrue(message.contains("add a method with the same annotation for version '1'"), message);
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

    // then the method serves no task of the deployed model. That is the other direction,
    // and it is not asked here
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

  @Test
  @DisplayName("An element of an extension whose methods all miss the deployed version ends the start")
  public void anElementOfAnExtensionWhoseMethodsMissTheVersionEndsTheStart() {

    registerNoteContract(null);
    register(PROCESS, NotesOfLaterVersions.class);
    reportElements(List.of(List.of("ServiceTask_AssessRisk", "assessRisk")));

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> deploy(ADAPTER, PROCESS, "1", List.of(ASSESS_RISK)));

    final var message = failure.getMessage();
    assertTrue(
        message.startsWith("Version '1' of BPMN process 'loan_approval' (workflow module 'loan-approval') is the "
            + "version adapter 'camunda7' deployed during this start."),
        message);
    assertTrue(message.contains(ELEMENTS_SERVED_BY_NO_METHOD_IN_THIS_VERSION), message);
    assertTrue(
        message.contains(
            "element 'ServiceTask_AssessRisk' (also matched by 'assessRisk'): @Note method '%s#noteAboutTheRisk' "
                .formatted(NotesOfLaterVersions.class.getName())
                + "of extension 'test-extension' (version '>1')"),
        "annotation, method, extension and range are named: "
            + message);
    assertTrue(
        message.contains("@Note method '%s#noteAboutTheWorkflow' of extension 'test-extension' (version '>1'), which "
            .formatted(NotesOfLaterVersions.class.getName())
            + "does not cover version '1'."),
        "the method serving every key may run for the element as well: "
            + message);
    assertTrue(
        message.contains("For @Note of extension 'test-extension': It finds no method to call for such an element "
            + "and goes on without one."),
        "the neutral sentence stands in for the extension's: "
            + message);
    assertFalse(message.contains("task 'ServiceTask_AssessRisk'"), "the task itself is served: "
        + message);
    assertFalse(message.contains(A_TASK_WITHOUT_A_METHOD_STOPS_EVERY_WORKFLOW), "no task stops: "
        + message);
    assertFalse(message.contains("@Remark"), "a contract which does not ask is not asked: "
        + message);
    assertTrue(message.contains("version = \">=1\""), message);

  }

  @Test
  @DisplayName("The extension says what an element without a method means")
  public void theExtensionSaysWhatTheMissingMethodMeans() {

    registerNoteContract("The note of such a task stays empty.");
    register(PROCESS, NotesOfLaterVersions.class);
    reportElements(List.of(List.of("ServiceTask_AssessRisk")));

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> deploy(ADAPTER, PROCESS, "1", List.of(ASSESS_RISK)));

    assertTrue(
        failure.getMessage().contains("For @Note of extension 'test-extension': The note of such a task stays empty."),
        failure.getMessage());
    assertTrue(failure.getMessage().contains("element 'ServiceTask_AssessRisk': @Note method"), failure.getMessage());

  }

  @Test
  @DisplayName("An element of an extension with a method covering the deployed version is served")
  public void anElementOfAnExtensionCoveredByOneMethodIsServed() {

    registerNoteContract(null);
    register(PROCESS, NotesOfEveryVersion.class);
    reportElements(List.of(List.of("ServiceTask_AssessRisk", "assessRisk")));

    assertDoesNotThrow(() -> deploy(ADAPTER, PROCESS, "1", List.of(ASSESS_RISK)));

  }

  @Test
  @DisplayName("An element no method of the extension serves at all is not asked about")
  public void anElementWithoutMethodsIsNotAsked() {

    registerNoteContract(null);
    register(PROCESS, NotesOfEveryVersion.class);
    // nothing serves the process itself, and no method is a legal answer
    reportElements(List.of(List.of("Activity_NobodyNotes"), List.of()));

    assertDoesNotThrow(() -> deploy(ADAPTER, PROCESS, "1", List.of(ASSESS_RISK)));

  }

  @Test
  @DisplayName("The process itself is served only by a method serving every key")
  public void theProcessItselfIsServedByEveryKeyMethodsOnly() {

    registerNoteContract(null);
    register(PROCESS, NotesOfLaterVersions.class);
    reportElements(List.of(List.of()));

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> deploy(ADAPTER, PROCESS, "1", List.of(ASSESS_RISK)));

    final var message = failure.getMessage();
    assertTrue(
        message.contains("the process itself: @Note method '%s#noteAboutTheWorkflow'"
            .formatted(NotesOfLaterVersions.class.getName())),
        message);
    assertFalse(message.contains("noteAboutTheRisk"), "a method of one element is no method of the process: "
        + message);

  }

  @Test
  @DisplayName("Each adapter holds the reported elements against the version its own BPMS gave the model")
  public void eachAdapterHoldsTheElementsAgainstItsOwnVersion() {

    registerNoteContract(null);
    register(PROCESS, NotesOfLaterVersions.class);
    reportElements(List.of(List.of("ServiceTask_AssessRisk")));
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
  @DisplayName("A task and an element of an extension end the start in one message, each with its sentence")
  public void aTaskAndAnElementShareOneMessage() {

    registerNoteContract(null);
    register(PROCESS, TaskAndNoteOfLaterVersions.class);
    reportElements(List.of(List.of("ServiceTask_AssessRisk")));

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> deploy(ADAPTER, PROCESS, "1", List.of(ASSESS_RISK)));

    final var message = failure.getMessage();
    assertTrue(message.contains("task 'ServiceTask_AssessRisk' (task definition 'assessRisk')"), message);
    assertTrue(message.contains("element 'ServiceTask_AssessRisk': @Note method"), message);
    assertTrue(message.contains(A_TASK_WITHOUT_A_METHOD_STOPS_EVERY_WORKFLOW), message);
    assertTrue(message.contains("For @Note of extension 'test-extension'"), message);

  }

  @Test
  @DisplayName("Without the flag the elements of a contract are not asked about")
  public void withoutTheFlagNothingIsAsked() {

    registry
        .getExtensionHandlers()
        .register(io.vanillabp.integration.extension.spi.handler.HandlerContract
            .of("test-extension", Note.class)
            .lookupKeys(annotation -> keysOf(((Note) annotation).element()))
            .versions(annotation -> versionsOf(((Note) annotation).version()))
            .callsCarryTheProcessVersion()
            .coreParameters(io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter.WORKFLOW_AGGREGATE)
            .build());
    register(PROCESS, NotesOfLaterVersions.class);
    reportElements(List.of(List.of("ServiceTask_AssessRisk"), List.of()));

    assertDoesNotThrow(() -> deploy(ADAPTER, PROCESS, "1", List.of(ASSESS_RISK)));

  }

  @Test
  @DisplayName("A contract asking for a method for the deployed version has to name the version on its calls")
  public void theFlagNeedsCallsCarryingTheVersion() {

    final var builder = io.vanillabp.integration.extension.spi.handler.HandlerContract
        .of("test-extension", Note.class)
        .theDeployedVersionNeedsAMethod();

    final var failure = assertThrows(IllegalArgumentException.class, builder::build);

    assertTrue(failure.getMessage().contains("theDeployedVersionNeedsAMethod()"), failure.getMessage());
    assertTrue(failure.getMessage().contains("callsCarryTheProcessVersion()"), failure.getMessage());
    assertTrue(failure.getMessage().contains("extension 'test-extension'"), failure.getMessage());
    assertTrue(failure.getMessage().contains("'@Note'"), failure.getMessage());

  }

  /**
   * Registers the contract of {@link Note}, which asks for a method for the deployed version.
   * It also registers the one of {@link Remark}, which does not.
   *
   * @param consequence What the extension says an element without a method means, or
   *          <code>null</code> for the neutral sentence
   */
  private void registerNoteContract(
      final String consequence) {

    final var note = io.vanillabp.integration.extension.spi.handler.HandlerContract
        .of("test-extension", Note.class)
        .lookupKeys(annotation -> keysOf(((Note) annotation).element()))
        .versions(annotation -> versionsOf(((Note) annotation).version()))
        .callsCarryTheProcessVersion()
        .coreParameters(io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter.WORKFLOW_AGGREGATE);
    registry
        .getExtensionHandlers()
        .register((consequence == null
            ? note.theDeployedVersionNeedsAMethod()
            : note.theDeployedVersionNeedsAMethod(consequence)).build());
    registry
        .getExtensionHandlers()
        .register(io.vanillabp.integration.extension.spi.handler.HandlerContract
            .of("other-extension", Remark.class)
            .lookupKeys(annotation -> keysOf(((Remark) annotation).element()))
            .versions(annotation -> versionsOf(((Remark) annotation).version()))
            .callsCarryTheProcessVersion()
            .coreParameters(io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter.WORKFLOW_AGGREGATE)
            .build());
    // the other extension reports the same element, and nobody asks it about it
    registry
        .getExtensionHandlers()
        .elementsOfTheDeployedModel(Remark.class, MODULE, PROCESS, List.of(List.of("ServiceTask_AssessRisk")));

  }

  /**
   * What the extension does in its <code>wireBpmn</code>.
   */
  private void reportElements(
      final List<List<String>> lookupKeysOfEachElement) {

    registry
        .getExtensionHandlers()
        .elementsOfTheDeployedModel(Note.class, MODULE, PROCESS, lookupKeysOfEachElement);

  }

  private static List<String> keysOf(
      final String element) {

    return element.isEmpty()
        ? List.of()
        : List.of(element);

  }

  private static List<String> versionsOf(
      final String version) {

    return version.isEmpty()
        ? List.of()
        : List.of(version);

  }

  /**
   * What an adapter does for one process. It wires the model, reports the version its BPMS
   * gave it, and hands over the catalog. Then the core resolves the versions of the module,
   * and the check runs there.
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
   * A BPMS which holds nothing but the version this start deployed. So no older version
   * adds a finding of its own.
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
