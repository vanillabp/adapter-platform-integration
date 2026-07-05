package io.vanillabp.adapter.dummy.springboot.processservice;

/**
 * Optional hook of the dummy adapter used by integration tests to observe (and
 * possibly fail) phase two of a two-phase workflow start. Provide a bean of this type
 * to get notified; throwing an exception makes the dispatch fail, so retry behavior of
 * {@link io.vanillabp.integration.adapter.spi.PhaseTwoOutbox} implementations can be
 * tested.
 */
@FunctionalInterface
public interface DummyAdapterPhaseTwoListener {

  /**
   * Called by the dummy adapter's {@link MigratableProcessService} whenever phase two
   * of starting a workflow is executed.
   *
   * @param workflowAggregateId The ID of the workflow aggregate
   */
  void startedWorkflowPhaseTwo(
      Object workflowAggregateId);

}
