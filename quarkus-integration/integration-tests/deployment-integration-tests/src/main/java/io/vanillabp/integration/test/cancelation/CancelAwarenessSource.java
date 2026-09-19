package io.vanillabp.integration.test.cancelation;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The BPMS the derived-cancellation test elects: it knows every workflow it is asked
 * about, and it writes down which id the election passed it.
 */
@ApplicationScoped
public class CancelAwarenessSource implements DummyTaskAwarenessSource {

  private final List<String> workflowIdsTheElectionPassed = new CopyOnWriteArrayList<>();

  /**
   * @return What the election passed as the BPMS' own id of the workflow, in the order it
   *         was asked, with <code>null</code> written as the text "null"
   */
  public List<String> getWorkflowIdsTheElectionPassed() {

    return List.copyOf(workflowIdsTheElectionPassed);

  }

  /**
   * Forgets what was asked so far.
   */
  public void forgetWhatWasAsked() {

    workflowIdsTheElectionPassed.clear();

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    return WorkflowAwareness.ACTIVE;

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final String adapterId,
      final Object workflowAggregateId,
      final String workflowId) {

    workflowIdsTheElectionPassed.add(String.valueOf(workflowId));
    return WorkflowAwareness.ACTIVE;

  }

}
