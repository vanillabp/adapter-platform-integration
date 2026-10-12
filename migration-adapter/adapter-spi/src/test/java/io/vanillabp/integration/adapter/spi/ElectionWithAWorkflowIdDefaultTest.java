package io.vanillabp.integration.adapter.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Older adapters were written before the election passed on the BPMS' own id of a
 * workflow. Such an adapter still compiles and answers exactly as before. This is why the
 * fourth argument breaks nothing. It gets a test because the default is only useful if
 * nobody has to notice it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ElectionWithAWorkflowIdDefaultTest {

  /**
   * An adapter written the old way. It implements the question with three arguments and
   * knows nothing about a workflow id.
   */
  private static class AnAdapterWrittenBefore implements MigratableProcessService<Object> {

    private Object askedAbout;

    @Override
    public WorkflowAwareness awarenessOfWorkflow(
        final WorkflowScope scope,
        final AggregatePersistenceAware<Object> aggregatePersistence,
        final Object workflowAggregateId) {

      askedAbout = workflowAggregateId;
      return WorkflowAwareness.ACTIVE;

    }

    @Override
    public String getAdapterId() {
      return "an-adapter";
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
    public Map<io.vanillabp.integration.spi.PhaseOperation, PhaseOperationHandler<Object>> phaseOperations() {
      return Map.of();
    }

  }

  @Test
  @DisplayName("An adapter which does not implement the new entry answers exactly as before")
  public void theDefaultDropsTheIdAndAsksTheOldQuestion() {

    final var adapter = new AnAdapterWrittenBefore();

    final var answer = adapter
        .awarenessOfWorkflow(null, null, "4711", "2251799813685249");

    assertEquals(WorkflowAwareness.ACTIVE, answer);
    assertEquals("4711", adapter.askedAbout, "the question which reached it is the one it implements");

  }

  @Test
  @DisplayName("And a caller which knows no id asks the same way")
  public void anAbsentIdChangesNothingEither() {

    final var adapter = new AnAdapterWrittenBefore();

    assertEquals(
        WorkflowAwareness.ACTIVE,
        adapter.awarenessOfWorkflow(null, null, "4712", null));
    assertEquals("4712", adapter.askedAbout);

  }

}
