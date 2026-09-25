package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.BusinessKeyCheck;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.workflowtask.WorkflowTaskRegistry;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TransactionRunner;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.BpmsStartTrigger;
import io.vanillabp.spi.service.WorkflowEnd;
import io.vanillabp.spi.service.WorkflowEnded;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowStartedByBpms;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * A business key counts only where it carries the workflow aggregate's id.
 * <p>
 * VanillaBP names a workflow by its workflow aggregate and by nothing else, and writes
 * that id into the business key wherever it starts the workflow itself. A workflow
 * started past VanillaBP can carry a key somebody else chose, and then two values say
 * different things about one instance. This test holds the three places where the BPMS
 * hands such a workflow over: a task delivery, the notification that the workflow ended,
 * and a start the BPMS performed on its own.
 * <p>
 * What a disagreement produces is a refusal, never an incident VanillaBP raises itself.
 * The invocation ends, and each BPMS applies what it applies to any failing handler. See
 * decision 69 in the repository's DECISIONS.md.
 */
@ExtendWith(SuppressOutputExtension.class)
public class BusinessKeyIsTheAggregateIdTest {

  private static final String MODULE = "business-key-module";

  private static final String PROCESS = "OrderingProcess";

  private static final String ADAPTER = "test-adapter";

  private static final String TIMER_EVENT = "DailyTimer";

  private static final Instant TRIGGER_TIME = Instant.parse("2026-09-17T04:00:00Z");

  public static class Aggregate {

    String id;

    String servedBy;

    public String getId() {
      return id;
    }

    public void setId(
        final String id) {
      this.id = id;
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

  /**
   * A transaction runner which really rolls back: it remembers the stored aggregates
   * before the work starts and puts them back when the work throws. Without that a test
   * cannot say whether a refused start left an aggregate behind, and that is half of
   * what a refusal before anything is written is worth.
   */
  static class TransactionRunnerStub implements TransactionRunner {

    private final InMemoryPersistence persistence;

    TransactionRunnerStub(
        final InMemoryPersistence persistence) {
      this.persistence = persistence;
    }

    @Override
    public <T> T requireNew(
        final Supplier<T> work) {
      final var before = new HashMap<>(persistence.aggregates);
      try {
        return work.get();
      } catch (final RuntimeException rolledBack) {
        persistence.aggregates.clear();
        persistence.aggregates.putAll(before);
        throw rolledBack;
      }
    }

    @Override
    public <T> T inCurrent(
        final Supplier<T> work) {
      return requireNew(work);
    }

    @Override
    public boolean isRollbackOnly() {
      return false;
    }

  }

  @WorkflowService(workflowAggregateClass = Aggregate.class, bpmnProcess = @BpmnProcess(bpmnProcessId = PROCESS))
  public static class OrderingService {

    static String endedFor;

    @WorkflowTask(id = "AssessRisk")
    public void assessRisk(
        final Aggregate aggregate) {

      aggregate.servedBy = "assessRisk";

    }

    @WorkflowEnded
    public void ended(
        final Aggregate aggregate,
        final WorkflowEnd end) {

      endedFor = aggregate.id;

    }

    @WorkflowStartedByBpms(id = TIMER_EVENT)
    public Aggregate startedByTheBpms(
        final BpmsStartTrigger trigger) {

      final var aggregate = new Aggregate();
      aggregate.id = "started-at-"
          + trigger.startEventId();
      return aggregate;

    }

  }

  private final InMemoryPersistence persistence = new InMemoryPersistence();

  private MigrationProcessService<Aggregate> processService() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();

    @SuppressWarnings("unchecked")
    final MigratableProcessService<Aggregate> adapter = mock(MigratableProcessService.class);
    lenient()
        .when(adapter.getAdapterId())
        .thenReturn(ADAPTER);

    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Aggregate.class)
        .properties(properties)
        .aggregatePersistence(persistence)
        .processServices(List.of(adapter))
        .build();

  }

  private WorkflowTaskRegistry registry() {

    final var registry = new WorkflowTaskRegistry(new TransactionRunnerStub(persistence));
    registry
        .registerWorkflowService(
            MODULE, PROCESS, OrderingService.class, OrderingService::new, type -> null, processService());
    return registry;

  }

  private Aggregate storeAggregate(
      final String id) {

    final var aggregate = new Aggregate();
    aggregate.id = id;
    persistence.aggregates.put(id, aggregate);
    return aggregate;

  }

  /** What an adapter reports about one delivery, business key included. */
  private static TaskInvocationContext delivery(
      final String aggregateId,
      final String businessKey) {

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
      public String getBusinessKey() {
        return businessKey;
      }

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

      @Override
      public String getWorkflowId() {
        return "2251799813685249";
      }

    };

  }

  private static WorkflowEndedContext ended(
      final String aggregateId,
      final String businessKey) {

    return new WorkflowEndedContext() {

      @Override
      public String getWorkflowAggregateId() {
        return aggregateId;
      }

      @Override
      public String getBusinessKey() {
        return businessKey;
      }

      @Override
      public WorkflowEnd.Kind getKind() {
        return WorkflowEnd.Kind.COMPLETED;
      }

      @Override
      public Instant getEndTime() {
        return TRIGGER_TIME;
      }

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

    };

  }

  private static BpmsInitiatedStartContext startedByTheBpms(
      final String businessKey) {

    return new BpmsInitiatedStartContext() {

      @Override
      public String getStartEventId() {
        return TIMER_EVENT;
      }

      @Override
      public BpmsStartTrigger.Kind getKind() {
        return BpmsStartTrigger.Kind.TIMER;
      }

      @Override
      public String getBusinessKey() {
        return businessKey;
      }

      @Override
      public String getAdapterId() {
        return ADAPTER;
      }

    };

  }

  @Test
  @DisplayName("A delivery whose business key is not the aggregate's id is refused, and the message names both")
  public void aDeliveryWithAForeignBusinessKeyIsRefused() {

    final var testee = registry();
    storeAggregate("4711");

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4711", "ORDER-2026-0815")));

    assertTrue(
        refused.getMessage().contains("'ORDER-2026-0815'"),
        "the business key the BPMS keeps: %s".formatted(refused.getMessage()));
    assertTrue(
        refused.getMessage().contains("'4711'"),
        "and the id of the workflow aggregate, which is the value that counts: %s"
            .formatted(refused.getMessage()));
    assertTrue(
        refused.getMessage().contains("'2251799813685249'"),
        "plus the instance, so the workflow can be found in the BPMS: %s".formatted(refused.getMessage()));
    assertTrue(
        refused.getMessage().contains("ProcessService"),
        "and the way out, because a reader must not have to look anything up: %s"
            .formatted(refused.getMessage()));
    assertNull(
        persistence.aggregates.get("4711").servedBy,
        "the method never ran: a workflow naming itself twice is not served");

  }

  @Test
  @DisplayName("A workflow without a business key runs unchanged")
  public void aWorkflowWithoutABusinessKeyRunsUnchanged() {

    final var testee = registry();
    final var aggregate = storeAggregate("4712");

    // every Camunda 8 workflow up to cluster 8.9 and every workflow of a BPMS which
    // keeps no business key looks like this, so this is the ordinary case
    testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4712", null));

    assertEquals("assessRisk", aggregate.servedBy);

  }

  @Test
  @DisplayName("A business key of nothing but spaces says nothing and contradicts nothing")
  public void aBlankBusinessKeyContradictsNothing() {

    final var testee = registry();
    final var aggregate = storeAggregate("4713");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4713", "   "));

    assertEquals("assessRisk", aggregate.servedBy);

  }

  @Test
  @DisplayName("A workflow taken over from version 1 carries a business key and no variable, which is silence")
  public void aWorkflowTakenOverFromVersionOneIsNotADeviation() {

    // Version 1 on Camunda 7 wrote no process variables at all: the model read the
    // workflow aggregate directly, so such an instance carries its identity in the
    // business key and nowhere else. An adapter which reads the id from the variable
    // finds none, and this is the case which must not become an incident - otherwise
    // every workflow an application takes over on its upgrade would fail at its first
    // delivery.
    assertDoesNotThrow(
        () -> BusinessKeyCheck
            .refuseAKeyWhichIsNotTheAggregateId(
                "4711", null, "The delivery of task 'assessRisk'", MODULE, PROCESS, ADAPTER, null),
        "a business key with nothing on the other side says nothing about a deviation");
    assertDoesNotThrow(
        () -> BusinessKeyCheck
            .refuseAKeyWhichIsNotTheAggregateId(
                "4711", "   ", "The delivery of task 'assessRisk'", MODULE, PROCESS, ADAPTER, null),
        "and neither does one held against a value of nothing but spaces");
    assertDoesNotThrow(
        () -> BusinessKeyCheck
            .refuseAKeyWhichIsNotTheAggregateId(
                null, null, "The delivery of task 'assessRisk'", MODULE, PROCESS, ADAPTER, null),
        "two absent values are two silences, not a disagreement");

  }

  @Test
  @DisplayName("A business key carrying the aggregate's id is what VanillaBP writes itself, and it passes")
  public void aBusinessKeyCarryingTheAggregateIdPasses() {

    final var testee = registry();
    final var aggregate = storeAggregate("4714");

    testee.invokeWorkflowTask(MODULE, PROCESS, delivery("4714", "4714"));

    assertEquals("assessRisk", aggregate.servedBy);

  }

  @Test
  @DisplayName("The end of a workflow whose business key says something else is refused as well")
  public void theEndOfAWorkflowWithAForeignBusinessKeyIsRefused() {

    final var testee = registry();
    storeAggregate("4715");
    OrderingService.endedFor = null;

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> testee.workflowEnded(MODULE, PROCESS, ended("4715", "ORDER-2026-0816")));

    assertTrue(
        refused.getMessage().contains("'ORDER-2026-0816'") && refused.getMessage().contains("'4715'"),
        "both values belong in the message here too: %s".formatted(refused.getMessage()));
    assertNull(OrderingService.endedFor, "and the application is not told about an end it cannot place");

  }

  @Test
  @DisplayName("The end of a workflow which carries the aggregate's id is reported")
  public void theEndOfAWorkflowNamingItselfOnceIsReported() {

    final var testee = registry();
    storeAggregate("4716");
    OrderingService.endedFor = null;

    testee.workflowEnded(MODULE, PROCESS, ended("4716", "4716"));

    assertEquals("4716", OrderingService.endedFor);

  }

  @Test
  @DisplayName("A start the BPMS performed on an instance carrying a foreign key leaves no aggregate behind")
  public void aBpmsInitiatedStartWithAForeignBusinessKeyLeavesNothingBehind() {

    final var testee = registry();

    final var refused = assertThrows(
        IllegalStateException.class,
        () -> testee.startWorkflowByBpms(MODULE, PROCESS, startedByTheBpms("ORDER-2026-0817")));

    assertTrue(
        refused.getMessage().contains("'ORDER-2026-0817'"),
        "the key the instance already carried: %s".formatted(refused.getMessage()));
    assertTrue(
        persistence.aggregates.isEmpty(),
        "the refusal happens inside the transaction the start opened, so nothing is left over");

  }

  @Test
  @DisplayName("A start the BPMS performed on an instance without a key builds the aggregate")
  public void aBpmsInitiatedStartWithoutABusinessKeyBuildsTheAggregate() {

    final var testee = registry();

    final var result = testee.startWorkflowByBpms(MODULE, PROCESS, startedByTheBpms(null));

    assertEquals(1, persistence.aggregates.size());
    assertTrue(result.created());

  }

}
