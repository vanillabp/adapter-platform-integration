package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.ExtensionWorkflowElection;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.processservice.RunningBpmsInitiatedStart;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.migration.workflowstart.BpmsInitiatedStartExecution;
import io.vanillabp.integration.adapter.migration.workflowstart.BpmsInitiatedStartScanner;
import io.vanillabp.integration.adapter.migration.workflowtask.InheritedVersions;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowStartedByBpms;

/**
 * A report of a changed aggregate made from inside the <code>&#64;WorkflowStartedByBpms</code>
 * method which builds that aggregate. On Camunda 8 the method runs in the job of the start
 * listener, before the row naming the BPMS of the workflow is written and while the cluster does
 * not show the workflow yet, so the report would ask a BPMS which has nothing to say. For an
 * aggregate the method is building, the report does nothing, and an extension can ask the same
 * question. For an aggregate which exists already, the report runs as always.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AReportInsideTheStartOfAWorkflowTest {

  private static final String MODULE = "report-inside-the-start";

  private static final String PROCESS = "RideProcess";

  private static final String ADAPTER = "c8";

  private static final String START_EVENT = "TimerStart";

  /** The workflow aggregate of the test. */
  public static class Ride {

    private String id;

    private String driver;

    public String getId() {

      return id;

    }

    public void setId(
        final String id) {

      this.id = id;

    }

    public String getDriver() {

      return driver;

    }

    public void setDriver(
        final String driver) {

      this.driver = driver;

    }

  }

  /**
   * The application's workflow service. Its method reports the aggregate it builds the way an
   * application does which calls {@code ProcessService#aggregateChanged} wherever it changes one,
   * and it writes down what an extension would be told at that moment.
   */
  public static class TheWorkflowService {

    static MigrationProcessService<Ride> processService;

    static WorkflowElection election;

    /** What the method builds; set by each test. */
    static Supplier<Ride> rideToReturn;

    static final List<Boolean> insideTheStartAnswers = new ArrayList<>();

    @WorkflowStartedByBpms
    public Ride startedByTheBpms(
        final BpmsStartTrigger trigger) {

      final var ride = rideToReturn.get();
      ride.setDriver("assigned on the timer");
      insideTheStartAnswers.add(election.isInsideTheStartOf(ride));
      return processService.aggregateChanged(ride, null);

    }

  }

  /** Stores the aggregates in memory and gives one an id where it has none. */
  static class RidesInMemory implements AggregatePersistenceAware<Ride> {

    final Map<String, Ride> stored = new ConcurrentHashMap<>();

    final AtomicInteger saves = new AtomicInteger();

    final AtomicInteger nextId = new AtomicInteger();

    volatile boolean canLoadById = true;

    @Override
    public Class<Ride> getAggregateClass() {

      return Ride.class;

    }

    @Override
    public Class<?> getAggregateIdType() {

      return String.class;

    }

    @Override
    public String getAggregateIdName() {

      return "id";

    }

    @Override
    public Ride save(
        final Ride ride) {

      saves.incrementAndGet();
      if (ride.getId() == null) {
        ride.setId("generated-%d".formatted(nextId.incrementAndGet()));
      }
      stored.put(ride.getId(), ride);
      return ride;

    }

    @Override
    public Object getAggregateId(
        final Ride ride) {

      return ride.getId();

    }

    @Override
    public Ride loadById(
        final Object aggregateId) {

      if (!canLoadById) {
        throw new UnsupportedOperationException("loadById is not implemented by this test double");
      }
      return stored.get(String.valueOf(aggregateId));

    }

  }

  /** A Camunda 8 cluster whose read model does not show the workflow yet, and which counts the questions. */
  static class AClusterWhichDoesNotShowTheWorkflowYet implements MigratableProcessService<Ride> {

    final AtomicInteger workflowProbes = new AtomicInteger();

    final AtomicInteger aggregateChangedPhaseTwo = new AtomicInteger();

    @Override
    public String getAdapterId() {

      return ADAPTER;

    }

    @Override
    public Map<PhaseOperation, PhaseOperationHandler<Ride>> phaseOperations() {

      final var operations = new HashMap<PhaseOperation, PhaseOperationHandler<Ride>>();
      PhaseOperation.CORE_OPERATIONS
          .forEach(operation -> operations
              .put(operation, PhaseOperationHandler.of(request -> {
              }, request -> {
                if (operation == PhaseOperation.AGGREGATE_CHANGED) {
                  aggregateChangedPhaseTwo.incrementAndGet();
                }
              })));
      return operations;

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
        final AggregatePersistenceAware<Ride> aggregatePersistence,
        final Object workflowAggregateId) {

      workflowProbes.incrementAndGet();
      return WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

  }

  /** Runs the work where it is asked to, without a database. */
  static class NoTransactions implements TransactionRunner {

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

  private final RidesInMemory rides = new RidesInMemory();

  private final AClusterWhichDoesNotShowTheWorkflowYet cluster = new AClusterWhichDoesNotShowTheWorkflowYet();

  private final AnOperationReadsTheStartRowTest.DeliveryLogInMemory deliveryLog = new AnOperationReadsTheStartRowTest.DeliveryLogInMemory();

  private final List<PhaseTwoCall> scheduled = new ArrayList<>();

  private MigrationProcessService<Ride> processService;

  @BeforeEach
  public void buildTheApplication() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    processService = MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Ride.class)
        .properties(properties)
        .aggregatePersistence(rides)
        .processServices(List.of(cluster))
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
    final var router = new PhaseTwoRouter();
    router.register(processService);
    TheWorkflowService.processService = processService;
    TheWorkflowService.election = new ExtensionWorkflowElection(router);
    TheWorkflowService.insideTheStartAnswers.clear();

  }

  @AfterEach
  public void forgetTheApplication() {

    TheWorkflowService.processService = null;
    TheWorkflowService.election = null;
    TheWorkflowService.rideToReturn = null;

  }

  private void theTimerFires() {

    final var handler = BpmsInitiatedStartScanner
        .scan(
            TheWorkflowService.class,
            Ride.class,
            TheWorkflowService::new,
            InheritedVersions.declaredFor(TheWorkflowService.class, PROCESS))
        .getFirst();
    BpmsInitiatedStartExecution
        .run(processService, handler, "no method serves the start", new BpmsInitiatedStartContext() {

          @Override
          public String getStartEventId() {

            return START_EVENT;

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

            return "2251799813685249";

          }

        }, new NoTransactions());

  }

  @Test
  @DisplayName("A report about an aggregate without an id asks no BPMS and plans nothing, and the start saves it once")
  public void aReportAboutANewAggregateDoesNothing() {

    TheWorkflowService.rideToReturn = Ride::new;

    theTimerFires();

    assertEquals(0, cluster.workflowProbes.get(), "the cluster does not show the workflow yet, so it was not asked");
    assertTrue(scheduled.isEmpty(), "the start hands the values over, so nothing is planned for them");
    assertEquals(1, rides.saves.get(), "only the start saves the aggregate");
    assertEquals(List.of(true), TheWorkflowService.insideTheStartAnswers);
    final var started = deliveryLog.workflowStartOf(MODULE, PROCESS, "generated-1");
    assertTrue(started.isPresent(), "the start wrote down which BPMS holds the workflow");
    assertEquals("assigned on the timer", rides.stored.get("generated-1").getDriver());

  }

  @Test
  @DisplayName("A report about an aggregate whose id the persistence does not know yet does nothing either")
  public void aReportAboutAnAggregateWithAnUnknownIdDoesNothing() {

    TheWorkflowService.rideToReturn = () -> {
      final var ride = new Ride();
      ride.setId("ride-of-the-morning");
      return ride;
    };

    theTimerFires();

    assertEquals(0, cluster.workflowProbes.get());
    assertTrue(scheduled.isEmpty());
    assertEquals(List.of(true), TheWorkflowService.insideTheStartAnswers);
    assertNotNull(rides.stored.get("ride-of-the-morning"));

  }

  @Test
  @DisplayName("A report about an aggregate which exists already runs as always")
  public void aReportAboutAnExistingAggregateRuns() {

    final var existing = new Ride();
    existing.setId("ride-with-history");
    rides.stored.put(existing.getId(), existing);
    // an earlier workflow of this aggregate, which is what lets the report be planned while the
    // cluster does not show the workflow
    deliveryLog
        .recordWorkflowStart(
            TaskDelivery.workflowStart(ADAPTER, MODULE, PROCESS, existing.getId(), "2251799813600000", "1",
                Instant.now()));
    TheWorkflowService.rideToReturn = () -> existing;

    theTimerFires();

    assertEquals(List.of(false), TheWorkflowService.insideTheStartAnswers);
    assertEquals(1, cluster.workflowProbes.get(), "the report asked the cluster");
    assertEquals(
        List.of(PhaseOperation.AGGREGATE_CHANGED.name()),
        scheduled
            .stream()
            .map(PhaseTwoCall::operation)
            .toList());

  }

  @Test
  @DisplayName("Outside the method nothing is inside a start, and where the persistence cannot load by id the report runs")
  public void onlyTheRunningStartOfThisProcessCounts() {

    final var withoutId = new Ride();
    final var withId = new Ride();
    withId.setId("ride-nobody-can-load");
    rides.canLoadById = false;

    assertFalse(processService.isInsideTheStartOf(withoutId), "no start runs");
    try (var running = RunningBpmsInitiatedStart.of(MODULE, "AnotherProcess")) {
      assertFalse(processService.isInsideTheStartOf(withoutId), "the start of another process runs");
    }
    try (var running = RunningBpmsInitiatedStart.of(MODULE, PROCESS)) {
      assertTrue(processService.isInsideTheStartOf(withoutId), "without an id the aggregate is new anyway");
      assertFalse(
          processService.isInsideTheStartOf(withId),
          "nothing tells whether an aggregate with this id exists, so it is treated as one which does");
      assertFalse(processService.isInsideTheStartOf("not a ride"));
    }
    assertFalse(processService.isInsideTheStartOf(withoutId), "the start is over");

  }

}
