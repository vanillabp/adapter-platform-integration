package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.config.AdapterConfigProperties;
import io.vanillabp.integration.adapter.migration.config.MigrationAdapterProperties;
import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.MigrationProcessService;
import io.vanillabp.integration.adapter.migration.processservice.ProcessDefinitionIds;
import io.vanillabp.integration.adapter.migration.processservice.TaskDeliveryLogResolver;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.WorkflowScope;
import io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.TaskDeliveryLog;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.WorkflowHistory;
import io.vanillabp.spi.process.WorkflowNotFoundException;

/**
 * A read of the viewer API on a node whose election cache knows nothing about the workflow.
 * That is a second node, a node after a restart, or a node whose cache forgot. The workflow
 * was started a moment ago, and the read model of its BPMS has not caught up yet. The row
 * written when the workflow started is in the database of the application. The read uses it
 * like an operation does. So it waits for the adapter which the row names, instead of
 * failing at once. Without the row, the read behaves as before.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheViewerReadsTheStartRowTest {

  private static final String MODULE = "viewer-start-row-module";

  private static final String PROCESS = "ViewerStartRowProcess";

  private static final String ADAPTER = "c8";

  private static final String WORKFLOW = "2251799813685249";

  private static final String AGGREGATE = "4711";

  /**
   * An adapter whose read model is behind for the first probes. It answers "unknown" until
   * the given number of probes has passed. It writes down the workflow id each probe was
   * handed.
   */
  static class AnAdapterWhichCatchesUp extends AnOperationReadsTheStartRowTest.AnAdapterWithALaggingReadModel {

    final AtomicInteger probes = new AtomicInteger();

    volatile int unknownForProbes;

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String workflowId) {

      workflowIdsHanded.add(String.valueOf(workflowId));
      return probes.incrementAndGet() > unknownForProbes
          ? answer
          : WorkflowAwareness.UNKNOWN_TO_BPMS;

    }

    @Override
    public WorkflowVisibilityDelay workflowVisibilityDelay() {

      return new WorkflowVisibilityDelay(Duration.ofSeconds(5), Duration.ofMillis(10));

    }

    @Override
    public WorkflowHistory getWorkflowHistory(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String historyContext) {

      return new WorkflowHistory("native:1:abc", OffsetDateTime.now(), null, List.of());

    }

  }

  /** The delivery log of the other test, plus the open tasks of an aggregate. */
  static class DeliveryLogWithOpenTasks extends AnOperationReadsTheStartRowTest.DeliveryLogInMemory {

    @Override
    public List<TaskDelivery> openTasksOfAggregate(
        final String workflowModuleId,
        final String bpmnProcessId,
        final String workflowAggregateId) {

      return records
          .values()
          .stream()
          .filter(row -> row.taskId() != null)
          .filter(row -> workflowAggregateId.equals(row.workflowAggregateId()))
          .toList();

    }

  }

  private final AnAdapterWhichCatchesUp adapter = new AnAdapterWhichCatchesUp();

  private final DeliveryLogWithOpenTasks deliveryLog = new DeliveryLogWithOpenTasks();

  /** What a node which just restarted has: an empty cache. */
  private MigrationProcessService<Object> aNodeWithoutAHint() {

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
        .workflowAdapterCache(new InMemoryWorkflowAdapterCache())
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

  private void theWorkflowWasStartedBy(
      final String adapterId) {

    deliveryLog
        .recordWorkflowStart(
            TaskDelivery.workflowStart(adapterId, MODULE, PROCESS, AGGREGATE, WORKFLOW, "1", Instant.now()));

  }

  private void aUserTaskOfTheWorkflowIsOpen() {

    deliveryLog
        .record(new TaskDelivery(
            "delivery-of-task", ADAPTER, MODULE, PROCESS, AGGREGATE, WORKFLOW, "approve", "Approve", "2251799813685300", "COMPLETION_PENDING", null, null, Instant
                .now(), null, "USER_TASK"));

  }

  @Test
  @DisplayName("With a start row and an empty cache the viewer waits for the young workflow and finds it")
  public void theStartRowLetsTheReadWait() {

    theWorkflowWasStartedBy(ADAPTER);
    adapter.answer = WorkflowAwareness.ACTIVE;
    adapter.unknownForProbes = 3;
    final var processService = aNodeWithoutAHint();

    final var history = processService.getWorkflowHistory(AGGREGATE, null);

    assertNotNull(history);
    assertEquals(ProcessDefinitionIds.compose(ADAPTER, "native:1:abc"), history.processDefinitionId());
    assertEquals(4, adapter.probes.get(), "three times unknown while the read model caught up, then found");
    assertTrue(
        adapter.workflowIdsHanded.stream().allMatch(WORKFLOW::equals),
        "every probe is handed the workflow id of the start row");

  }

  @Test
  @DisplayName("A start row which proved right fills the cache, so the next read does not wait")
  public void aRowWhichProvedRightFillsTheCache() {

    theWorkflowWasStartedBy(ADAPTER);
    adapter.answer = WorkflowAwareness.ACTIVE;
    adapter.unknownForProbes = 1;
    final var processService = aNodeWithoutAHint();

    processService.getWorkflowHistory(AGGREGATE, null);
    processService.getWorkflowHistory(AGGREGATE, null);

    assertEquals(3, adapter.probes.get(), "two probes for the first read, one for the second");

  }

  @Test
  @DisplayName("Without a start row and without a hint a workflow nobody reports fails at once, as before")
  public void withoutARowTheReadFailsAtOnce() {

    adapter.answer = WorkflowAwareness.ACTIVE;
    adapter.unknownForProbes = Integer.MAX_VALUE;
    final var processService = aNodeWithoutAHint();

    final var started = System.nanoTime();
    final var exception = assertThrows(
        WorkflowNotFoundException.class,
        () -> processService.getWorkflowHistory(AGGREGATE, null));

    assertEquals(1, adapter.probes.get(), "asked once, nothing to wait for");
    assertTrue(
        Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(1)) < 0,
        "without any hint the read does not use the visibility window");
    assertEquals(List.of("null"), adapter.workflowIdsHanded);
    assertTrue(exception.getMessage().contains("No configured BPMS knows"), exception.getMessage());

  }

  @Test
  @DisplayName("A start row naming an adapter the configuration dropped does not count for a read either")
  public void aRowOfADroppedAdapterDoesNotCount() {

    theWorkflowWasStartedBy("c7-retired");
    adapter.unknownForProbes = Integer.MAX_VALUE;
    final var processService = aNodeWithoutAHint();

    assertThrows(WorkflowNotFoundException.class, () -> processService.getWorkflowHistory(AGGREGATE, null));

    assertEquals(1, adapter.probes.get());
    assertEquals(List.of("null"), adapter.workflowIdsHanded,
        "the id of the dropped adapter's workflow is not handed on");

  }

  @Test
  @DisplayName("Without a start row the probe of a read is handed the workflow id of an open task")
  public void theRowOfAnOpenTaskGivesTheWorkflowId() {

    aUserTaskOfTheWorkflowIsOpen();
    adapter.answer = WorkflowAwareness.ACTIVE;
    final var processService = aNodeWithoutAHint();

    processService.getWorkflowHistory(AGGREGATE, null);

    assertEquals(List.of(WORKFLOW), adapter.workflowIdsHanded);

  }

}
