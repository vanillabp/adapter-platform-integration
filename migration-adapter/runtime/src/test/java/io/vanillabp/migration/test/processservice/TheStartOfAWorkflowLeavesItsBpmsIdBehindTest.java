package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.ExtensionWorkflowElection;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoOutboxResolver;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.DeliveryRecordKind;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.spi.WorkflowStartKey;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What happens when a workflow starts: the adapter which created the instance reports its id into
 * the request of phase two, and the core writes the pair "this aggregate, that workflow" down.
 * From then on the id can be read without asking any BPMS, which is what the election of an
 * operation about a workflow does today - it probes every configured adapter and remembers nothing.
 * <p>
 * An adapter which reports nothing is in here as well, because it has to keep behaving exactly as
 * it did: no row, no id, and the probing which was there before.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheStartOfAWorkflowLeavesItsBpmsIdBehindTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private static final String ADAPTER = "c8";

  private static final String AGGREGATE = "4711";

  private static final String WORKFLOW = "2251799813685249";

  /**
   * An adapter which creates an instance in phase two and says what it called it. Which is the
   * only moment anybody ever learns that name: phase two returns nothing.
   */
  static class AnAdapterWhichSaysWhatItCreated implements MigratableProcessService<Object> {

    private final String workflowItCreates;

    final List<String> phaseTwoRan = new ArrayList<>();

    AnAdapterWhichSaysWhatItCreated(
        final String workflowItCreates) {

      this.workflowItCreates = workflowItCreates;

    }

    @Override
    public String getAdapterId() {

      return ADAPTER;

    }

    @Override
    public Map<PhaseOperation, PhaseOperationHandler<Object>> phaseOperations() {

      final var operations = new HashMap<PhaseOperation, PhaseOperationHandler<Object>>();
      PhaseOperation.CORE_OPERATIONS
          .forEach(
              operation -> operations
                  .put(operation, PhaseOperationHandler.of(request -> {
                  }, this::createTheInstance)));
      return operations;

    }

    private void createTheInstance(
        final PhaseTwoRequest<Object> request) {

      phaseTwoRan.add(String.valueOf(request.workflowAggregateId()));
      if (workflowItCreates != null) {
        request.reportStartedWorkflow(workflowItCreates);
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
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {

      return WorkflowAwareness.ACTIVE;

    }

    @Override
    public boolean deliversTasksAtLeastOnce() {

      return true;

    }

  }

  /** A delivery log in memory which answers by key, which is all the start row needs. */
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
          .filter(record -> workflowAggregateId.equals(record.workflowAggregateId()))
          .filter(record -> "COMPLETION_PENDING".equals(record.outcome()))
          .toList();

    }

  }

  private final DeliveryLogInMemory deliveryLog = new DeliveryLogInMemory();

  private final List<PhaseTwoCall> scheduled = new ArrayList<>();

  private static AggregatePersistenceAware<Object> persistence() {

    return new AggregatePersistenceAware<>() {

      @Override
      public Class<Object> getAggregateClass() {

        return Object.class;

      }

      @Override
      public Class<?> getAggregateIdType() {

        return String.class;

      }

      @Override
      public Object save(
          final Object workflowAggregate) {

        return workflowAggregate;

      }

      @Override
      public Object getAggregateId(
          final Object workflowAggregate) {

        return AGGREGATE;

      }

    };

  }

  private static MigrationAdapterProperties properties() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    return properties;

  }

  private MigrationProcessService<Object> serviceOf(
      final MigratableProcessService<Object> adapter) {

    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Object.class)
        .properties(properties())
        .aggregatePersistence(persistence())
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

  }

  /**
   * Phase two of the start, which is what the outbox dispatches once the caller's transaction
   * committed.
   */
  private static void dispatchTheStart(
      final MigrationProcessService<Object> service) {

    service.executePhaseTwo(PhaseOperation.START_WORKFLOW, AGGREGATE, ADAPTER, Map.of(), false);

  }

  @Test
  @DisplayName("Phase two of a start writes down which workflow of the BPMS it created")
  public void phaseTwoOfAStartWritesTheWorkflowDown() {

    final var service = serviceOf(new AnAdapterWhichSaysWhatItCreated(WORKFLOW));

    dispatchTheStart(service);

    final var start = deliveryLog.records
        .get(WorkflowStartKey.of(MODULE, PROCESS, AGGREGATE));
    assertEquals(WORKFLOW, start.workflowId());
    assertEquals(ADAPTER, start.adapterId());
    assertEquals(DeliveryRecordKind.WORKFLOW_START.name(), start.recordKind());
    assertNull(start.taskId());
    assertNull(start.outcome());

  }

  @Test
  @DisplayName("The id of a workflow is read without asking any BPMS")
  public void theIdIsReadWithoutAskingAnyBpms() {

    final var service = serviceOf(new AnAdapterWhichSaysWhatItCreated(WORKFLOW));
    dispatchTheStart(service);

    assertEquals(WORKFLOW, service.workflowIdOf(AGGREGATE));

    final var router = new PhaseTwoRouter();
    router.register(service);
    final var election = new ExtensionWorkflowElection(router);

    assertEquals(Optional.of(WORKFLOW), election.workflowIdOf(MODULE, PROCESS, AGGREGATE));
    assertTrue(
        election.workflowIdOf(MODULE, PROCESS, "4712").isEmpty(),
        "an aggregate nothing was started for is the same empty answer as an expired row");
    final var refused = org.junit.jupiter.api.Assertions.assertThrows(
        IllegalStateException.class,
        () -> election.workflowIdOf(MODULE, "AnotherProcess", AGGREGATE),
        "a process this application does not serve is refused, as the election refuses it");
    assertTrue(refused.getMessage().contains("No @WorkflowService of this application declares BPMN process "
        + "'AnotherProcess'"), refused.getMessage());

  }

  @Test
  @DisplayName("An adapter which reports no workflow leaves no row and nothing is claimed")
  public void anAdapterWhichSaysNothingLeavesNothing() {

    final var service = serviceOf(new AnAdapterWhichSaysWhatItCreated(null));

    dispatchTheStart(service);

    assertTrue(deliveryLog.records.isEmpty(), "no id, no row - exactly as before the row existed");
    assertNull(service.workflowIdOf(AGGREGATE));

  }

  @Test
  @DisplayName("A start dispatched twice writes nothing the second time")
  public void aStartDispatchedTwiceWritesOneRow() {

    final var service = serviceOf(new AnAdapterWhichSaysWhatItCreated(WORKFLOW));

    dispatchTheStart(service);
    dispatchTheStart(service);

    assertEquals(1, deliveryLog.records.size());

  }

  @Test
  @DisplayName("The start answers once the records of the tasks are gone")
  public void theStartAnswersWhenTheTasksAreGone() {

    final var service = serviceOf(new AnAdapterWhichSaysWhatItCreated(WORKFLOW));
    dispatchTheStart(service);

    // what answered this question before: an OPEN task delivery of that aggregate. It is gone the
    // moment the application completes the task, and then only the start row is left
    deliveryLog
        .record(
            new TaskDelivery(
                "a", ADAPTER, MODULE, PROCESS, AGGREGATE, "another-instance", "awaitSignature", null, "job-1", "COMPLETED", null, null, Instant
                    .now(), null));

    assertTrue(
        deliveryLog.openTasksOfAggregate(MODULE, PROCESS, AGGREGATE).isEmpty(),
        "a delivery which closed its task is no open task, so it answers nothing");
    assertEquals(
        WORKFLOW,
        service.workflowIdOf(AGGREGATE),
        "the start row is read first, and it holds the workflow the aggregate IS");

  }

}
