package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

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
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.extension.spi.election.WorkflowLocation;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The election an extension asks for, answered from the row written when the workflow started.
 * That row names the adapter which started the workflow, and a workflow does not change its BPMS,
 * so where the row is there no BPMS is asked and nothing is waited for. Where it is not there, or
 * names an adapter the configuration dropped, the election probes as it always did.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheElectionReadsTheStartRowFirstTest {

  private static final String MODULE = "election-module";

  private static final String PROCESS = "ElectedProcess";

  private static final String ADAPTER = "c8";

  private static final String WORKFLOW = "2251799813685249";

  /**
   * An adapter which starts workflows, reports their ids and counts how often it is asked whether
   * it holds one. What it answers to that question is set by the test.
   */
  static class AnAdapterWhichCountsItsProbes implements MigratableProcessService<Object> {

    final AtomicInteger probes = new AtomicInteger();

    volatile WorkflowAwareness answer = WorkflowAwareness.ACTIVE;

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
                  }, request -> request.reportStartedWorkflow(WORKFLOW, "1"))));
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
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {

      probes.incrementAndGet();
      return answer;

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

  }

  private final AnAdapterWhichCountsItsProbes adapter = new AnAdapterWhichCountsItsProbes();

  private final DeliveryLogInMemory deliveryLog = new DeliveryLogInMemory();

  private final List<PhaseTwoCall> scheduled = new ArrayList<>();

  private final MigrationProcessService<Object> processService = processService();

  private MigrationProcessService<Object> processService() {

    final var properties = MigrationAdapterProperties
        .builder()
        .adapters(Map.of(ADAPTER, AdapterConfigProperties.ofType("dummy")))
        .prioritizedAdapters(List.of(ADAPTER))
        .build();
    properties.validateAndLink();
    return MigrationProcessService
        .forBpmnProcess(MODULE, PROCESS, Object.class)
        .properties(properties)
        .aggregatePersistence(new AggregatePersistenceAware<>() {

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

            return workflowAggregate;

          }

        })
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

  private WorkflowElection election() {

    final var router = new PhaseTwoRouter();
    router.register(processService);
    return new ExtensionWorkflowElection(router);

  }

  private void dispatchTheStartOf(
      final String aggregateId) {

    processService.executePhaseTwo(PhaseOperation.START_WORKFLOW, aggregateId, ADAPTER, Map.of(), false);
    adapter.probes.set(0);

  }

  @Test
  @DisplayName("With a start row no BPMS is asked, and the answer carries the workflow of the row")
  public void theStartRowAnswersWithoutAskingTheBpms() {

    dispatchTheStartOf("4711");

    assertEquals(new WorkflowLocation(ADAPTER, WORKFLOW), election().locationOfWorkflow(MODULE, PROCESS, "4711"));
    assertEquals(ADAPTER, election().adapterIdOfWorkflow(MODULE, PROCESS, "4711"));
    assertEquals(0, adapter.probes.get(), "the adapter was never asked whether it holds the workflow");

  }

  @Test
  @DisplayName("After the end of the workflow the start row still answers, where the election would throw")
  public void theStartRowOutlivesTheWorkflow() {

    dispatchTheStartOf("4711");
    // the BPMS forgot the workflow, which is what a cleaned-up history of an ended one looks like
    adapter.answer = WorkflowAwareness.UNKNOWN_TO_BPMS;

    assertEquals(ADAPTER, election().adapterIdOfWorkflow(MODULE, PROCESS, "4711"));
    assertEquals(0, adapter.probes.get());

  }

  @Test
  @DisplayName("Without a start row the election asks the BPMS as it always did")
  public void withoutARowTheElectionProbes() {

    assertEquals(ADAPTER, election().adapterIdOfWorkflow(MODULE, PROCESS, "4799"));
    assertTrue(adapter.probes.get() > 0, "no row, so the adapter had to be asked");

    adapter.answer = WorkflowAwareness.UNKNOWN_TO_BPMS;
    assertThrows(
        IllegalStateException.class,
        () -> election().adapterIdOfWorkflow(MODULE, PROCESS, "4798"),
        "and a workflow no BPMS knows is still an error where no row speaks for it");

  }

  @Test
  @DisplayName("A start row naming an adapter the configuration dropped is passed over")
  public void aRowOfADroppedAdapterIsPassedOver() {

    deliveryLog
        .recordWorkflowStart(
            TaskDelivery.workflowStart("c7-retired", MODULE, PROCESS, "4711", "old-instance", null, Instant.now()));

    assertEquals(ADAPTER, election().adapterIdOfWorkflow(MODULE, PROCESS, "4711"));
    assertTrue(adapter.probes.get() > 0, "the row named no configured adapter, so the election asked");

  }

}
