package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.adapter.migration.processservice.WorkflowLocator;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The election matrix of the {@link WorkflowLocator} (stories 22/25): the walk -
 * ACTIVE stops it, UNKNOWN_TO_BPMS falls through to the next adapter, COMPLETED is
 * reported to the caller, BPMS_UNAVAILABLE never falls back (retry then fail) -
 * plus the optional cache: consulted first, populated on success, repaired on
 * stale hits, never consulted-around on an unavailable cached adapter.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowLocatorTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  /**
   * A process service answering a fixed awareness sequence (one element per probe
   * call) - the last element repeats.
   */
  static class ProbeAdapter implements MigratableProcessService<Object> {

    private final String adapterId;

    private final List<WorkflowAwareness> answers;

    final AtomicInteger probes = new AtomicInteger();

    ProbeAdapter(
        final String adapterId,
        final WorkflowAwareness... answers) {

      this.adapterId = adapterId;
      this.answers = List.of(answers);

    }

    WorkflowAwareness answer() {

      final var index = probes.getAndIncrement();
      return answers.get(Math.min(index, answers.size() - 1));

    }

    @Override
    public String getAdapterId() {
      return adapterId;
    }

    @Override
    public WorkflowAwareness awarenessOfTask(
        final Object workflowAggregateId,
        final String taskId) {
      return answer();
    }

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final Object workflowAggregateId) {
      return answer();
    }

    @Override
    public boolean needsTwoPhaseCommitForStartingWorkflows() {
      return false;
    }

    @Override
    public void startWorkflowPhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate) {
    }

    @Override
    public void startWorkflowPhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {
    }

    @Override
    public void completeTaskPhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate,
        final String taskId) {
    }

    @Override
    public void completeTaskPhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String taskId) {
    }

    @Override
    public void cancelTaskPhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate,
        final String taskId,
        final String bpmnErrorCode) {
    }

    @Override
    public void cancelTaskPhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String taskId,
        final String bpmnErrorCode) {
    }

    @Override
    public io.vanillabp.integration.adapter.spi.WorkflowAwareness awarenessOfUserTask(
        final Object workflowAggregateId,
        final String taskId) {
      return io.vanillabp.integration.adapter.spi.WorkflowAwareness.UNKNOWN_TO_BPMS;
    }

    @Override
    public void completeUserTaskPhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate,
        final String taskId) {
    }

    @Override
    public void completeUserTaskPhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String taskId) {
    }

    @Override
    public void cancelUserTaskPhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate,
        final String taskId,
        final String bpmnErrorCode) {
    }

    @Override
    public void cancelUserTaskPhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String taskId,
        final String bpmnErrorCode) {
    }

    @Override
    public void correlateMessagePhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate,
        final String messageName,
        final String correlationId) {
    }

    @Override
    public void correlateMessagePhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String messageName,
        final String correlationId) {
    }

    @Override
    public void startWorkflowByMessagePhaseOne(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregate,
        final String messageName) {
    }

    @Override
    public void startWorkflowByMessagePhaseTwo(
        final String workflowModuleId,
        final String bpmnProcessId,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId,
        final String messageName) {
    }

  }

  private static WorkflowLocator.Location<Object> locate(
      final ProbeAdapter... adapters) {

    return locate(null, adapters);

  }

  private static WorkflowLocator.Location<Object> locate(
      final io.vanillabp.integration.spi.WorkflowAdapterCache cache,
      final ProbeAdapter... adapters) {

    return new WorkflowLocator(MODULE, PROCESS, cache)
        .locate(
            List.of(adapters),
            adapter -> adapter.awarenessOfTask("42", "task-1"),
            "42",
            "task 'task-1' of workflow aggregate '42'");

  }

  @Test
  @DisplayName("ACTIVE stops the walk - later adapters are not probed")
  public void activeStopsTheWalk() {

    final var first = new ProbeAdapter("first", WorkflowAwareness.ACTIVE);
    final var second = new ProbeAdapter("second", WorkflowAwareness.ACTIVE);

    final var location = locate(first, second);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());
    assertSame(first, location.adapter());
    assertEquals(0, second.probes.get(), "the second adapter must not be probed");

  }

  @Test
  @DisplayName("UNKNOWN_TO_BPMS falls through to the next adapter")
  public void unknownFallsThrough() {

    final var first = new ProbeAdapter("first", WorkflowAwareness.UNKNOWN_TO_BPMS);
    final var second = new ProbeAdapter("second", WorkflowAwareness.ACTIVE);

    final var location = locate(first, second);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());
    assertSame(second, location.adapter());

  }

  @Test
  @DisplayName("COMPLETED is reported with the answering adapter")
  public void completedIsReported() {

    final var first = new ProbeAdapter("first", WorkflowAwareness.UNKNOWN_TO_BPMS);
    final var second = new ProbeAdapter("second", WorkflowAwareness.COMPLETED);

    final var location = locate(first, second);

    assertEquals(WorkflowAwareness.COMPLETED, location.awareness());
    assertSame(second, location.adapter());

  }

  @Test
  @DisplayName("No adapter knows the task - UNKNOWN_TO_BPMS without an adapter")
  public void nobodyKnows() {

    final var first = new ProbeAdapter("first", WorkflowAwareness.UNKNOWN_TO_BPMS);
    final var second = new ProbeAdapter("second", WorkflowAwareness.UNKNOWN_TO_BPMS);

    final var location = locate(first, second);

    assertEquals(WorkflowAwareness.UNKNOWN_TO_BPMS, location.awareness());
    assertNull(location.adapter());

  }

  @Test
  @DisplayName("BPMS_UNAVAILABLE recovering within the retries continues the walk")
  public void unavailableRecoversWithinRetries() {

    // first probe unavailable, first retry active
    final var flaky = new ProbeAdapter(
        "flaky", WorkflowAwareness.BPMS_UNAVAILABLE, WorkflowAwareness.ACTIVE);

    final var location = locate(flaky);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());
    assertSame(flaky, location.adapter());
    assertEquals(2, flaky.probes.get());

  }

  @Test
  @DisplayName("BPMS_UNAVAILABLE never falls back - retries exhaust and the walk fails naming the adapter")
  public void unavailableNeverFallsBack() {

    final var down = new ProbeAdapter("down-adapter", WorkflowAwareness.BPMS_UNAVAILABLE);
    final var second = new ProbeAdapter("second", WorkflowAwareness.ACTIVE);

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> locate(down, second));

    assertTrue(
        failure.getMessage().contains("down-adapter"),
        "expected the unavailable adapter to be named but got: "
            + failure.getMessage());
    // initial probe + the retries
    assertEquals(1 + WorkflowLocator.UNAVAILABLE_RETRIES, down.probes.get());
    assertEquals(0, second.probes.get(), "falling back to another adapter is forbidden");

  }

  @Test
  @DisplayName("The pluggable probe decides what is asked - awarenessOfWorkflow works the same way")
  public void probeIsPluggable() {

    final var adapter = new ProbeAdapter("first", WorkflowAwareness.ACTIVE);

    final var location = new WorkflowLocator(MODULE, PROCESS, null)
        .locate(
            List.<MigratableProcessService<Object>>of(adapter),
            candidate -> candidate.awarenessOfWorkflow(Map.of()),
            "42",
            "workflow of aggregate '42'");

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());

  }

  @Test
  @DisplayName("A successful election populates the cache - the next election probes only the cached adapter")
  public void cachePopulatedOnSuccess() {

    final var cache = new InMemoryWorkflowAdapterCache();
    final var first = new ProbeAdapter("first", WorkflowAwareness.UNKNOWN_TO_BPMS);
    final var second = new ProbeAdapter("second", WorkflowAwareness.ACTIVE);

    // first election: full walk, second adapter wins and is cached
    final var firstLocation = locate(cache, first, second);
    assertSame(second, firstLocation.adapter());
    assertEquals("second", cache.get(MODULE, PROCESS, "42").orElseThrow());

    // second election: the cached adapter is probed directly, the first-priority
    // adapter is not asked again
    final var secondLocation = locate(cache, first, second);
    assertSame(second, secondLocation.adapter());
    assertEquals(1, first.probes.get(), "the cache hit must skip the walk");
    assertEquals(2, second.probes.get());

  }

  @Test
  @DisplayName("A stale cache hit falls through to the full walk and repairs the entry")
  public void staleCacheHitIsRepaired() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, "42", "second");
    final var first = new ProbeAdapter("first", WorkflowAwareness.ACTIVE);
    final var second = new ProbeAdapter("second", WorkflowAwareness.UNKNOWN_TO_BPMS);

    final var location = locate(cache, first, second);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());
    assertSame(first, location.adapter());
    // the stale hint is probed once; the walk then stops at the first adapter
    assertEquals(1, second.probes.get());
    assertEquals(1, first.probes.get());
    assertEquals("first", cache.get(MODULE, PROCESS, "42").orElseThrow(), "the entry must be repaired");

  }

  @Test
  @DisplayName("A COMPLETED answer on a cache hit is reported and drops the entry")
  public void completedCacheHitDropsTheEntry() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, "42", "second");
    final var first = new ProbeAdapter("first", WorkflowAwareness.ACTIVE);
    final var second = new ProbeAdapter("second", WorkflowAwareness.COMPLETED);

    final var location = locate(cache, first, second);

    assertEquals(WorkflowAwareness.COMPLETED, location.awareness());
    assertSame(second, location.adapter());
    assertEquals(0, first.probes.get(), "the cache hit must skip the walk");
    assertTrue(cache.get(MODULE, PROCESS, "42").isEmpty(), "a completed workflow's entry is dropped");

  }

  @Test
  @DisplayName("BPMS_UNAVAILABLE on a cached adapter never falls through - retry then fail naming the adapter")
  public void unavailableCachedAdapterNeverFallsThrough() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, "42", "down-adapter");
    final var down = new ProbeAdapter("down-adapter", WorkflowAwareness.BPMS_UNAVAILABLE);
    final var second = new ProbeAdapter("second", WorkflowAwareness.ACTIVE);

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> locate(cache, down, second));

    assertTrue(
        failure.getMessage().contains("down-adapter"),
        "expected the unavailable adapter to be named but got: "
            + failure.getMessage());
    assertEquals(1 + WorkflowLocator.UNAVAILABLE_RETRIES, down.probes.get());
    assertEquals(0, second.probes.get(), "falling back/through on unavailability is forbidden");
    assertEquals(
        "down-adapter",
        cache.get(MODULE, PROCESS, "42").orElseThrow(),
        "an unavailable adapter's entry is kept - it most probably still holds the workflow");

  }

  @Test
  @DisplayName("A cached adapter no longer prioritized drops the hint and elects from the current configuration")
  public void cachedAdapterNoLongerConfigured() {

    final var cache = new InMemoryWorkflowAdapterCache();
    cache.put(MODULE, PROCESS, "42", "removed-adapter");
    final var first = new ProbeAdapter("first", WorkflowAwareness.ACTIVE);

    final var location = locate(cache, first);

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());
    assertSame(first, location.adapter());
    assertEquals("first", cache.get(MODULE, PROCESS, "42").orElseThrow(), "the entry must be repaired");

  }

}
