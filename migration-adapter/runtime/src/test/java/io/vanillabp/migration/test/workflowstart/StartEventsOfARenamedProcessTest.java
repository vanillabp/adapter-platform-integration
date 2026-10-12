package io.vanillabp.migration.test.workflowstart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import ch.qos.logback.classic.Level;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.workflowstart.BpmsInitiatedStarts;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartSpec;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;
import lombok.Getter;

/**
 * The <code>&#64;WorkflowStartedByBpms</code> methods kept for a BPMN process id which the
 * application DECLARES without deploying a model for it. A renamed process leaves such an id
 * behind, and the BPMS may still fire its timer every day.
 * <p>
 * Nothing wires such an id during a boot. So the check which an adapter triggers while wiring
 * never runs for it, and nothing used to judge its methods. A typo in <code>id = ...</code>
 * stayed silent for the life of the application. The judgement needs the start events of a
 * version the BPMS holds. The adapter answers them through
 * {@link ProcessVersionCatalog#startEventsOfVersion}.
 * <p>
 * The BPMS is a stub, because the comparison of the core is under test here. The adapters
 * prove against real engines that reading an old model gives the right answer.
 */
@ExtendWith(SuppressOutputExtension.class)
public class StartEventsOfARenamedProcessTest {

  private static final String MODULE = "rename-module";

  private static final String NEW_ID = "OrderApproval";

  private static final String OLD_ID = "order_approval";

  private static final String ADAPTER = "c8";

  private static final String TIMER_EVENT = "DailyTimer";

  @Getter
  public static class Aggregate {

    String id;

  }

  /**
   * The application after the rename, with a method kept for the timer of the OLD model. Only
   * the application starts the new generation. The old one still fires.
   */
  @WorkflowService(
      workflowAggregateClass = Aggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = NEW_ID),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = OLD_ID, version = "1-3"))
  public static class ServiceKeepingTheOldTimer {

    @WorkflowTask(taskDefinition = "approve")
    public void approve(
        final Aggregate aggregate) {
    }

    @WorkflowStartedByBpms(id = TIMER_EVENT)
    public Aggregate startedByTheOldTimer(
        final BpmsStartTrigger trigger) {

      return new Aggregate();

    }

  }

  /**
   * The same application, but with the start event misspelled. Nothing used to find this
   * case, because the model which carries the right spelling is the one the application
   * does not bring.
   */
  @WorkflowService(
      workflowAggregateClass = Aggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = NEW_ID),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = OLD_ID, version = "1-3"))
  public static class ServiceWithAMisspelledStartEvent {

    @WorkflowTask(taskDefinition = "approve")
    public void approve(
        final Aggregate aggregate) {
    }

    @WorkflowStartedByBpms(id = "DailyTimerr")
    public Aggregate neverRuns(
        final BpmsStartTrigger trigger) {

      return new Aggregate();

    }

  }

  /**
   * A method kept for the old id, although no version the BPMS holds under it starts on its
   * own at all. The method belongs to another process, or the id is the misspelled one.
   */
  @WorkflowService(
      workflowAggregateClass = Aggregate.class,
      bpmnProcess = @BpmnProcess(bpmnProcessId = NEW_ID),
      secondaryBpmnProcesses = @BpmnProcess(bpmnProcessId = OLD_ID, version = "1-3"))
  public static class ServiceStartedByNothing {

    @WorkflowTask(taskDefinition = "approve")
    public void approve(
        final Aggregate aggregate) {
    }

    @WorkflowStartedByBpms
    public Aggregate neverRuns(
        final BpmsStartTrigger trigger) {

      return new Aggregate();

    }

  }

  /**
   * What a BPMS holds per BPMN process id. The test hands in every answer.
   */
  private static class CatalogStub implements ProcessVersionCatalog {

    private final Map<String, List<DeployedProcessVersion>> versions = new java.util.HashMap<>();

    /**
     * The start events which the BPMS fires itself, per "process id|version". A version
     * missing from this map has none. A version listed in
     * {@link #versionsWhoseModelCannotBeRead} answers <code>null</code>.
     */
    private final Map<String, Collection<BpmsInitiatedStartSpec>> startEvents = new java.util.HashMap<>();

    private final java.util.Set<String> versionsWhoseModelCannotBeRead = new java.util.HashSet<>();

    /**
     * Every question this BPMS was asked, in order. A test should read what a boot asks
     * about an id under which nothing was deployed.
     */
    private final List<String> questions = new ArrayList<>();

    @Override
    public List<DeployedProcessVersion> deployedVersionsOf(
        final String workflowModuleId,
        final String bpmnProcessId) {

      questions.add("deployedVersionsOf(%s)".formatted(bpmnProcessId));
      return versions.getOrDefault(bpmnProcessId, List.of());

    }

    @Override
    public DeployedProcessVersion resolveVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String versionOrVersionTag) {

      return versions
          .getOrDefault(bpmnProcessId, List.of())
          .stream()
          .filter(version -> version.version().equals(versionOrVersionTag))
          .findFirst()
          .orElse(null);

    }

    @Override
    public Collection<BpmsInitiatedStartSpec> startEventsOfVersion(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String version) {

      final var key = "%s|%s".formatted(bpmnProcessId, version);
      questions.add("startEventsOfVersion(%s)".formatted(key));
      return versionsWhoseModelCannotBeRead.contains(key)
          ? null
          : startEvents.getOrDefault(key, List.of());

    }

  }

  private MigrationAdapterProperties properties;

  private WorkflowTaskRegistry registry;

  private CatalogStub catalog;

  @BeforeEach
  public void setUp() {

    properties = new MigrationAdapterProperties();
    properties.setAdapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("camunda8")));
    registry = new WorkflowTaskRegistry(new TransactionRunnerStub(), null, List.of(), properties);

    catalog = new CatalogStub();
    catalog.versions
        .put(OLD_ID, List
            .of(DeployedProcessVersion.of("1"), DeployedProcessVersion.of("2"), DeployedProcessVersion.of("3")));
    catalog.versions.put(NEW_ID, List.of(DeployedProcessVersion.of("1")));
    // the timer was dropped along the way. The newest version which the BPMS holds under the
    // old id has none. That is why a check which reads only one version would say the wrong
    // thing
    catalog.startEvents
        .put(
            "%s|1".formatted(OLD_ID),
            List.of(BpmsInitiatedStartSpec.of(TIMER_EVENT, BpmsStartTrigger.Kind.TIMER)));
    catalog.startEvents
        .put(
            "%s|2".formatted(OLD_ID),
            List.of(BpmsInitiatedStartSpec.of(TIMER_EVENT, BpmsStartTrigger.Kind.TIMER)));

  }

  @Test
  @DisplayName("A method serving the timer of a version the BPMS holds is not reported")
  public void aMethodServingAHeldStartEventIsQuiet() {

    theApplicationDeclares(ServiceKeepingTheOldTimer.class);
    theAdapterDeployed(NEW_ID, "1");

    assertEquals(List.of(), theModuleFinishedDeploying(Level.WARN));
    assertTrue(
        catalog.questions.contains("startEventsOfVersion(%s|1)".formatted(OLD_ID)),
        () -> "the BPMS was never asked about the start events of the old id: "
            + catalog.questions);

  }

  @Test
  @DisplayName("A misspelled start event of a declared-only id is named, with the events which exist")
  public void aMisspelledStartEventIsNamed() {

    theApplicationDeclares(ServiceWithAMisspelledStartEvent.class);
    theAdapterDeployed(NEW_ID, "1");

    final var reports = theModuleFinishedDeploying(Level.WARN);

    assertEquals(1, reports.size(), reports.toString());
    assertTrue(reports.get(0).contains("'DailyTimerr'"), reports.get(0));
    assertTrue(reports.get(0).contains(OLD_ID), reports.get(0));
    assertTrue(reports.get(0).contains("'%s'".formatted(TIMER_EVENT)), reports.get(0));
    assertTrue(reports.get(0).contains("never runs"), reports.get(0));

  }

  @Test
  @DisplayName("Where no held version starts on its own, the whole declaration is questioned")
  public void aDeclaredIdWithoutABpmsInitiatedStartIsReported() {

    catalog.startEvents.clear();
    theApplicationDeclares(ServiceStartedByNothing.class);
    theAdapterDeployed(NEW_ID, "1");

    final var reports = theModuleFinishedDeploying(Level.WARN);

    assertEquals(1, reports.size(), reports.toString());
    assertTrue(reports.get(0).contains("none of the version(s)"), reports.get(0));
    assertTrue(reports.get(0).contains("1, 2, 3"), reports.get(0));
    // a misspelled declaration is compared against the ids which the module DID deploy
    assertTrue(reports.get(0).contains("'%s'".formatted(NEW_ID)), reports.get(0));

  }

  @Test
  @DisplayName("A BPMS which cannot read one of its models says nothing about that id")
  public void aModelWhichCannotBeReadSilencesTheCheck() {

    catalog.versionsWhoseModelCannotBeRead.add("%s|3".formatted(OLD_ID));
    theApplicationDeclares(ServiceWithAMisspelledStartEvent.class);
    theAdapterDeployed(NEW_ID, "1");

    // the start event might sit in exactly the model which could not be read. So a verdict
    // would be a guess (decision 38 in the repository's DECISIONS.md)
    assertEquals(List.of(), theModuleFinishedDeploying(Level.WARN));

  }

  @Test
  @DisplayName("A process this boot deployed is judged by its model, not by the versions held")
  public void aDeployedProcessIsNotJudgedHere() {

    theApplicationDeclares(ServiceWithAMisspelledStartEvent.class);
    theAdapterDeployed(NEW_ID, "1");
    theAdapterDeployed(OLD_ID, "3");

    // both ids were wired, so nothing is only declared any more. The wiring check judges
    // the methods. That is the call of the adapter, not of this check
    assertEquals(List.of(), theModuleFinishedDeploying(Level.WARN));
    assertTrue(
        catalog.questions.stream().noneMatch(question -> question.startsWith("startEventsOfVersion")),
        () -> "a deployed process was asked about the start events of its held versions: "
            + catalog.questions);

  }

  private void theApplicationDeclares(
      final Class<?> workflowServiceClass) {

    java.util.stream.Stream
        .concat(
            java.util.stream.Stream.of(workflowServiceClass.getAnnotation(WorkflowService.class).bpmnProcess()),
            java.util.stream.Stream
                .of(workflowServiceClass.getAnnotation(WorkflowService.class).secondaryBpmnProcesses()))
        .map(BpmnProcess::bpmnProcessId)
        .forEach(bpmnProcessId -> registry
            .registerWorkflowService(
                MODULE,
                bpmnProcessId,
                workflowServiceClass,
                () -> null,
                type -> null,
                processService()));

  }

  /**
   * What an adapter does for a BPMN process it really deployed. It wires the model,
   * registers what its BPMS knows about that process, and reports the version it was
   * given.
   */
  private void theAdapterDeployed(
      final String bpmnProcessId,
      final String version) {

    registry
        .validateTaskWiring(MODULE, bpmnProcessId, List.of(new BpmnTaskSpec("Activity_approve", "approve")));
    registry.registerProcessVersions(ADAPTER, MODULE, bpmnProcessId, catalog);
    registry.registerDeployedVersion(ADAPTER, MODULE, bpmnProcessId, version);

  }

  /**
   * The two steps which the deployment pipeline runs once every adapter of a workflow module
   * has deployed. First the core asks each adapter about the ids under which nothing was
   * deployed. Then the versions are resolved and checked.
   */
  private List<String> theModuleFinishedDeploying(
      final Level level) {

    final BiFunction<String, String, ProcessVersionCatalog> catalogOfProcess = (
        module,
        process) -> catalog;
    final var logWatcher = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory
        .getLogger(BpmsInitiatedStarts.class);
    logger.addAppender(logWatcher);
    try {
      registry.registerVersionsOfProcessesNobodyDeployed(MODULE, ADAPTER, catalogOfProcess);
      registry.resolveProcessVersions(MODULE);
    } finally {
      logger.detachAndStopAllAppenders();
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(level))
        .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
        .toList();

  }

  /**
   * The registry needs a process service to register a workflow service. Nothing here
   * invokes it.
   */
  @SuppressWarnings("unchecked")
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
