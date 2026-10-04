package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.DeliveryRecords;
import io.vanillabp.integration.adapter.migration.processservice.ExtensionWorkflowElection;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion;
import io.vanillabp.integration.adapter.spi.version.ProcessVersionCatalog;
import io.vanillabp.integration.adapter.spi.version.ReportedProcessVersion;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.election.WorkflowStart;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.DeliveryRecordKind;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.spi.WorkflowStartKey;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The version of the process definition a workflow runs on, written into the rows of the delivery
 * log on each of the three ways a row comes about: the start VanillaBP dispatches, the start the
 * BPMS fires itself, and the delivery of a task. An extension which shows details per version reads
 * it back together with the id of the workflow, and learns whether an empty version may still come.
 * <p>
 * An adapter which keeps a catalog of versions and reports none is in here as well. Its rows are
 * written all the same, and it is named once per BPMN process.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheRowsCarryTheProcessVersionTest {

  private static final String MODULE = "version-module";

  private static final String PROCESS = "VersionedProcess";

  private static final String ADAPTER = "c8";

  private static final String WORKFLOW = "2251799813685249";

  private static final String TIMER_EVENT = "DailyTimer";

  /**
   * The words of the WARN about a missing version, quoted by the case which expects it and by the
   * case which must not get it.
   */
  private static final String NO_VERSION_ALTHOUGH_A_CATALOG = "without the version of the process definition, although it keeps a catalog";

  public static class Aggregate {

    String id;

    public String getId() {
      return id;
    }

    public void setId(
        final String id) {
      this.id = id;
    }

  }

  @WorkflowService(workflowAggregateClass = Aggregate.class, bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class VersionedService {

    @WorkflowTask(id = "AssessRisk")
    public void assessRisk(
        final Aggregate aggregate) {

    }

    @WorkflowStartedByBpms(id = TIMER_EVENT)
    public Aggregate startedByTheBpms() {

      final var aggregate = new Aggregate();
      aggregate.id = "started-by-the-bpms";
      return aggregate;

    }

  }

  static class InMemoryPersistence implements AggregatePersistenceAware<Aggregate> {

    final Map<Object, Aggregate> aggregates = new HashMap<>();

    @Override
    public Class<Aggregate> getAggregateClass() {
      return Aggregate.class;
    }

    @Override
    public String getAggregateIdName() {
      return "id";
    }

    @Override
    public Class<?> getAggregateIdType() {
      return String.class;
    }

    @Override
    public Object getAggregateId(
        final Aggregate aggregate) {
      return aggregate.id;
    }

    @Override
    public Aggregate save(
        final Aggregate aggregate) {
      aggregates.put(aggregate.id, aggregate);
      return aggregate;
    }

    @Override
    public Aggregate loadById(
        final Object aggregateId) {
      return aggregates.get(aggregateId);
    }

  }

  static class TransactionRunnerStub implements TransactionRunner {

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

  /**
   * An adapter which creates an instance in phase two of a start and reports it, with the version
   * of the process definition or, the way an adapter written before the version did, without.
   */
  static class AnAdapterWhichReportsItsStarts implements MigratableProcessService<Aggregate> {

    private final String versionItNames;

    private final boolean callsTheMethodWithAVersion;

    AnAdapterWhichReportsItsStarts(
        final String versionItNames,
        final boolean callsTheMethodWithAVersion) {

      this.versionItNames = versionItNames;
      this.callsTheMethodWithAVersion = callsTheMethodWithAVersion;

    }

    @Override
    public String getAdapterId() {

      return ADAPTER;

    }

    @Override
    public Map<PhaseOperation, PhaseOperationHandler<Aggregate>> phaseOperations() {

      final var operations = new HashMap<PhaseOperation, PhaseOperationHandler<Aggregate>>();
      PhaseOperation.CORE_OPERATIONS
          .forEach(
              operation -> operations
                  .put(operation, PhaseOperationHandler.of(request -> {
                  }, this::createTheInstance)));
      return operations;

    }

    private void createTheInstance(
        final PhaseTwoRequest<Aggregate> request) {

      if (callsTheMethodWithAVersion) {
        request.reportStartedWorkflow(WORKFLOW, versionItNames);
      } else {
        request.reportStartedWorkflow(WORKFLOW);
      }

    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {

      return WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Override
    public WorkflowAwareness awarenessOfUserTask(
        final WorkflowScope scope,
        final Object workflowAggregateId,
        final String taskId) {

      return WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<Aggregate> aggregatePersistence,
        final Object workflowAggregateId) {

      return WorkflowAwareness.ACTIVE;

    }

  }

  /** A delivery log in memory, which keeps what it is told and answers by key and by aggregate. */
  static class DeliveryLogInMemory implements TaskDeliveryLog {

    final Map<String, TaskDelivery> records = new HashMap<>();

    @Override
    public Optional<TaskDelivery> recordedDelivery(
        final String deliveryKey) {

      return Optional.ofNullable(records.get(deliveryKey));

    }

    @Override
    public boolean record(
        final TaskDelivery delivery) {

      return records.putIfAbsent(delivery.deliveryKey(), delivery) == null;

    }

    @Override
    public List<TaskDelivery> openTasksOfAggregate(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {

      return records
          .values()
          .stream()
          .filter(record -> DeliveryRecordKind.of(record.recordKind()) == DeliveryRecordKind.TASK_DELIVERY)
          .filter(record -> bpmnProcessId.equals(record.bpmnProcessId()))
          .filter(record -> workflowAggregateId.equals(record.workflowAggregateId()))
          .filter(record -> "COMPLETION_PENDING".equals(record.outcome()))
          .toList();

    }

    TaskDelivery startOf(
        final String aggregateId) {

      return records.get(WorkflowStartKey.of(MODULE, PROCESS, aggregateId));

    }

    List<TaskDelivery> deliveries() {

      return records
          .values()
          .stream()
          .filter(record -> DeliveryRecordKind.of(record.recordKind()) == DeliveryRecordKind.TASK_DELIVERY)
          .toList();

    }

  }

  private final InMemoryPersistence persistence = new InMemoryPersistence();

  private final DeliveryLogInMemory deliveryLog = new DeliveryLogInMemory();

  private final List<PhaseTwoCall> scheduled = new ArrayList<>();

  private MigrationProcessService<Aggregate> processService;

  private WorkflowTaskRegistry registry;

  /**
   * The registry with one workflow service and one adapter, the way a platform integration builds
   * it at startup.
   */
  private void givenAnAdapterWhich(
      final AnAdapterWhichReportsItsStarts adapter) {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    processService = MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Aggregate.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(adapter))
        .phaseTwoOutboxResolver(new PhaseTwoOutboxResolver() {

          @Override
          public PhaseTwoOutbox resolveFor(
              final Class<?> workflowAggregateClass) {

            return scheduled::add;

          }

          @Override
          public String remediesDescription() {

            return "";

          }

          @Override
          public java.util.Collection<PhaseTwoOutbox> allStores() {

            return List.of();

          }

        })
        .taskDeliveryLogResolver(new TaskDeliveryLogResolver() {

          @Override
          public TaskDeliveryLog resolveFor(
              final Class<?> workflowAggregateClass) {

            return deliveryLog;

          }

          @Override
          public String remediesDescription() {

            return "";

          }

        })
        .build();
    registry = new WorkflowTaskRegistry(new TransactionRunnerStub());
    registry
        .registerWorkflowService(
            MODULE, PROCESS, VersionedService.class, VersionedService::new, type -> null, processService);

  }

  /** What an adapter whose BPMS counts versions says while it wires its BPMN. */
  private void givenTheAdapterKeepsACatalog() {

    registry.registerProcessVersions(ADAPTER, MODULE, PROCESS, new ProcessVersionCatalog() {

      @Override
      public List<DeployedProcessVersion> deployedVersionsOf(
          final String workflowModuleId,
          final String bpmnProcessId) {

        return List.of(DeployedProcessVersion.of("3", null));

      }

      @Override
      public DeployedProcessVersion resolveVersion(
          final String workflowModuleId,
          final String bpmnProcessId,
          final String versionOrVersionTag) {

        return null;

      }

    });

  }

  private void dispatchTheStartOf(
      final String aggregateId) {

    processService.executePhaseTwo(PhaseOperation.START_WORKFLOW, aggregateId, ADAPTER, Map.of(), false);

  }

  private WorkflowElection election() {

    final var router = new PhaseTwoRouter();
    router.register(processService);
    return new ExtensionWorkflowElection(router);

  }

  /**
   * Runs the work and collects what the delivery records warned about while it ran.
   */
  private List<String> warningsWhile(
      final Runnable work) {

    final var logWatcher = new ListAppender<ILoggingEvent>();
    logWatcher.start();
    final var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(DeliveryRecords.class);
    logger.addAppender(logWatcher);
    try {
      work.run();
    } finally {
      logger.detachAppender(logWatcher);
      logWatcher.stop();
    }
    return logWatcher.list
        .stream()
        .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
        .map(ILoggingEvent::getFormattedMessage)
        .toList();

  }

  private static TaskInvocationContext deliveryOf(
      final String aggregateId,
      final String processVersion) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return "AssessRisk";
      }

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getWorkflowId() {
        return WORKFLOW;
      }

      @Override
      public String getProcessVersion() {
        return processVersion;
      }

    };

  }

  @Test
  @DisplayName("Phase two of a start writes down the version the adapter names, and an extension reads it with the id")
  public void theStartOfTheApplicationCarriesTheVersion() {

    givenAnAdapterWhich(new AnAdapterWhichReportsItsStarts("3", true));
    givenTheAdapterKeepsACatalog();

    dispatchTheStartOf("4711");

    assertEquals("3", deliveryLog.startOf("4711").processVersion());
    assertEquals(
        Optional.of(new WorkflowStart(ADAPTER, WORKFLOW, "3", true)),
        election().workflowStartOf(MODULE, PROCESS, "4711"));
    assertEquals(
        Optional.of(WORKFLOW),
        election().workflowIdOf(MODULE, PROCESS, "4711"),
        "the id alone is read as before");

  }

  @Test
  @DisplayName("A start the BPMS fires itself carries the version its notification reports")
  public void theStartOfTheBpmsCarriesTheVersion() {

    givenAnAdapterWhich(new AnAdapterWhichReportsItsStarts(null, false));
    givenTheAdapterKeepsACatalog();

    registry.startWorkflowByBpms(MODULE, PROCESS, new BpmsInitiatedStartContext() {

      @Override
      public String getStartEventId() {
        return TIMER_EVENT;
      }

      @Override
      public BpmsStartTrigger.Kind getKind() {
        return BpmsStartTrigger.Kind.TIMER;
      }

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getNativeInstanceId() {
        return WORKFLOW;
      }

      @Override
      public String getProcessVersion() {
        return "5";
      }

    });

    final var start = deliveryLog.startOf("started-by-the-bpms");
    assertEquals(WORKFLOW, start.workflowId());
    assertEquals("5", start.processVersion(), "no adapter had to change for this one");

  }

  @Test
  @DisplayName("The row of a delivery carries the version the delivery reports")
  public void aDeliveryCarriesTheVersion() {

    givenAnAdapterWhich(new AnAdapterWhichReportsItsStarts(null, false));
    givenTheAdapterKeepsACatalog();
    final var aggregate = new Aggregate();
    aggregate.id = "4711";
    persistence.save(aggregate);

    registry.invokeWorkflowTask(MODULE, PROCESS, deliveryOf("4711", "4"));

    final var deliveries = deliveryLog.deliveries();
    assertEquals(1, deliveries.size());
    assertEquals("4", deliveries.getFirst().processVersion(), "no adapter had to change for this one");

  }

  @Test
  @DisplayName("An adapter with a catalog which names no version is named once, and its rows are written anyway")
  public void anAdapterWithACatalogWhichNamesNoVersionIsNamedOnce() {

    // the adapter calls the method it called before the version existed, which is exactly the
    // state every adapter is in until it follows
    givenAnAdapterWhich(new AnAdapterWhichReportsItsStarts(null, false));
    givenTheAdapterKeepsACatalog();

    final var warnings = warningsWhile(() -> {
      dispatchTheStartOf("4711");
      dispatchTheStartOf("4712");
    });

    assertEquals(
        1,
        warnings
            .stream()
            .filter(warning -> warning.contains(NO_VERSION_ALTHOUGH_A_CATALOG))
            .count(),
        "once per adapter and BPMN process, not once per row: "
            + warnings);
    assertTrue(warnings.getFirst().contains("'"
        + ADAPTER
        + "'"), warnings.getFirst());
    assertTrue(
        warnings.getFirst().contains("PhaseTwoRequest#reportStartedWorkflow(String, String)"),
        "the message names what the adapter has to call: "
            + warnings.getFirst());
    assertEquals(WORKFLOW, deliveryLog.startOf("4711").workflowId(), "nothing is stopped");
    assertEquals(WORKFLOW, deliveryLog.startOf("4712").workflowId(), "nothing is stopped");
    assertEquals(
        Optional.of(new WorkflowStart(ADAPTER, WORKFLOW, null, true)),
        election().workflowStartOf(MODULE, PROCESS, "4711"),
        "the version is missing, and the reader learns that this adapter is one which reports versions");

  }

  @Test
  @DisplayName("An adapter which said it has no versions is not named, and its reader learns not to wait")
  public void anAdapterWithoutVersionsIsNotNamed() {

    givenAnAdapterWhich(new AnAdapterWhichReportsItsStarts(null, true));
    registry.reportNoProcessVersionCatalog(ADAPTER, MODULE, PROCESS, ReportedProcessVersion.NONE);

    final var warnings = warningsWhile(() -> dispatchTheStartOf("4711"));

    assertFalse(
        warnings
            .stream()
            .anyMatch(warning -> warning.contains(NO_VERSION_ALTHOUGH_A_CATALOG)),
        "an empty version is no defect of an adapter whose BPMS has none: "
            + warnings);
    assertEquals(
        Optional.of(new WorkflowStart(ADAPTER, WORKFLOW, null, false)),
        election().workflowStartOf(MODULE, PROCESS, "4711"));

  }

  @Test
  @DisplayName("An adapter whose deliveries carry a version tag reports versions, without a catalog")
  public void anAdapterReportingTagsReportsVersions() {

    givenAnAdapterWhich(new AnAdapterWhichReportsItsStarts("release-7", true));
    registry.reportNoProcessVersionCatalog(ADAPTER, MODULE, PROCESS, ReportedProcessVersion.VERSION_TAG);

    dispatchTheStartOf("4711");

    assertEquals(
        Optional.of(new WorkflowStart(ADAPTER, WORKFLOW, "release-7", true)),
        election().workflowStartOf(MODULE, PROCESS, "4711"));

  }

  @Test
  @DisplayName("A start row without a version is answered with the version of an open task of that workflow")
  public void anOpenTaskFillsTheVersionTheStartLacks() {

    givenAnAdapterWhich(new AnAdapterWhichReportsItsStarts(null, false));
    givenTheAdapterKeepsACatalog();
    dispatchTheStartOf("4711");
    deliveryLog
        .record(
            new TaskDelivery(
                "open-task", ADAPTER, MODULE, PROCESS, "4711", WORKFLOW, "AssessRisk", "AssessRisk", "job-1", "COMPLETION_PENDING", null, null, Instant
                    .now(), null, "TASK", DeliveryRecordKind.TASK_DELIVERY.name(), "3"));

    assertEquals(
        Optional.of(new WorkflowStart(ADAPTER, WORKFLOW, "3", true)),
        election().workflowStartOf(MODULE, PROCESS, "4711"));

  }

  @Test
  @DisplayName("An aggregate nothing was started for, or a process this application does not serve, answers nothing")
  public void nothingKnownIsNothingAnswered() {

    givenAnAdapterWhich(new AnAdapterWhichReportsItsStarts("3", true));

    assertTrue(election().workflowStartOf(MODULE, PROCESS, "4799").isEmpty());
    assertTrue(election().workflowStartOf(MODULE, "AnotherProcess", "4711").isEmpty());

  }

  @Test
  @DisplayName("An election written before the version existed answers the id without a version")
  public void anOlderElectionAnswersTheIdOnly() {

    final WorkflowElection olderElection = new WorkflowElection() {

      @Override
      public String adapterIdOfWorkflow(
          final String workflowModuleId,
          final String bpmnProcessId,
          final Object workflowAggregateId) {

        return ADAPTER;

      }

      @Override
      public Optional<String> workflowIdOf(
          final String workflowModuleId,
          final String bpmnProcessId,
          final Object workflowAggregateId) {

        return Optional.of(WORKFLOW);

      }

    };

    assertEquals(
        Optional.of(new WorkflowStart(null, WORKFLOW, null, false)),
        olderElection.workflowStartOf(MODULE, PROCESS, "4711"));

  }

  @Test
  @DisplayName("A sink written before the version existed still hears the id")
  public void anOlderSinkHearsTheId() {

    final var heard = new ArrayList<String>();
    final var request = new PhaseTwoRequest<Aggregate>(
        MODULE, PROCESS, persistence, "4711", Map.of(), heard::add);

    request.reportStartedWorkflow(WORKFLOW, "3");

    assertEquals(List.of(WORKFLOW), heard);
    assertDoesNotThrow(
        () -> new PhaseTwoRequest<Aggregate>(MODULE, PROCESS, persistence, "4711", Map.of())
            .reportStartedWorkflow(WORKFLOW, "3"),
        "a request nobody listens to drops both");

  }

}
