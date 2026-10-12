package io.vanillabp.integration.test.persistence;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Tells the dummy adapter that it knows every task it is asked about. So operations on a
 * running workflow reach phase two and are not rejected as unknown.
 */
@ApplicationScoped
public class ActiveTaskAwarenessSource implements DummyTaskAwarenessSource {

  @Override
  public WorkflowAwareness awarenessOfTask(
      final String adapterId,
      final Object workflowAggregateId,
      final String taskId) {

    return WorkflowAwareness.ACTIVE;

  }

}
