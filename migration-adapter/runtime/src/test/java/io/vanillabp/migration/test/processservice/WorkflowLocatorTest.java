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

import io.vanillabp.integration.adapter.migration.processservice.WorkflowLocator;
import io.vanillabp.integration.adapter.spi.MigratableProcessService;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The probing matrix of the {@link WorkflowLocator} walk (story 22): ACTIVE stops
 * the walk, UNKNOWN_TO_BPMS falls through to the next adapter, COMPLETED is
 * reported to the caller, BPMS_UNAVAILABLE never falls back (retry then fail).
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowLocatorTest {

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

  }

  private static WorkflowLocator.Location<Object> locate(
      final ProbeAdapter... adapters) {

    return WorkflowLocator.locate(
        List.of(adapters),
        adapter -> adapter.awarenessOfTask("42", "task-1"),
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

    final var location = WorkflowLocator.locate(
        List.<MigratableProcessService<Object>>of(adapter),
        candidate -> candidate.awarenessOfWorkflow(Map.of()),
        "workflow of aggregate '42'");

    assertEquals(WorkflowAwareness.ACTIVE, location.awareness());

  }

}
