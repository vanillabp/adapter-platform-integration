package io.vanillabp.adapter.dummy.runtime;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import jakarta.enterprise.inject.Instance;

public class MigratableProcessService<A> implements io.vanillabp.integration.adapter.spi.MigratableProcessService<A> {

  private final String adapterId;

  private final boolean deliversTasksAtLeastOnce;

  private final boolean readsAggregateInPhaseTwo;

  private final Instance<DummyPhaseTwoListener> phaseTwoListeners;

  private final Instance<DummyTaskAwarenessSource> taskAwarenessSources;

  private final Instance<DummyViewerSource> viewerSources;

  public MigratableProcessService(
      final String adapterId,
      final boolean deliversTasksAtLeastOnce,
      final boolean readsAggregateInPhaseTwo,
      final Instance<DummyPhaseTwoListener> phaseTwoListeners,
      final Instance<DummyTaskAwarenessSource> taskAwarenessSources,
      final Instance<DummyViewerSource> viewerSources) {

    this.adapterId = adapterId;
    this.deliversTasksAtLeastOnce = deliversTasksAtLeastOnce;
    this.readsAggregateInPhaseTwo = readsAggregateInPhaseTwo;
    this.phaseTwoListeners = phaseTwoListeners;
    this.taskAwarenessSources = taskAwarenessSources;
    this.viewerSources = viewerSources;

  }

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
      final Object workflowAggregateId,
      final String taskId) {

    // tests steer the answer via DummyTaskAwarenessSource beans; without one the
    // dummy does not know any task
    if (taskAwarenessSources != null) {
      return taskAwarenessSources
          .stream()
          .map(source -> source.awarenessOfTask(adapterId, workflowAggregateId, taskId))
          .filter(java.util.Objects::nonNull)
          .findFirst()
          .orElse(WorkflowAwareness.UNKNOWN_TO_BPMS);
    }
    return WorkflowAwareness.UNKNOWN_TO_BPMS;

  }

  @Override
  public WorkflowAwareness awarenessOfUserTask(
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
      final Object workflowAggregateId,
      final String taskId) {

    if (taskAwarenessSources != null) {
      return taskAwarenessSources
          .stream()
          .map(source -> source.awarenessOfUserTask(adapterId, workflowAggregateId, taskId))
          .filter(java.util.Objects::nonNull)
          .findFirst()
          .orElse(WorkflowAwareness.UNKNOWN_TO_BPMS);
    }
    return WorkflowAwareness.UNKNOWN_TO_BPMS;

  }

  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
      final io.vanillabp.integration.spi.AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    if (taskAwarenessSources != null) {
      return taskAwarenessSources
          .stream()
          .map(source -> source.awarenessOfWorkflow(adapterId, workflowAggregateId))
          .filter(java.util.Objects::nonNull)
          .findFirst()
          .orElse(WorkflowAwareness.UNKNOWN_TO_BPMS);
    }
    return WorkflowAwareness.UNKNOWN_TO_BPMS;

  }


  @Override
  public io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay workflowVisibilityDelay() {

    if (taskAwarenessSources == null) {
      return io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay.none();
    }
    return taskAwarenessSources
        .stream()
        .map(source -> source.workflowVisibilityDelay(adapterId))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElseGet(io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay::none);

  }

  @Override
  public boolean isPhaseTwoFailureRepeatable(
      final Throwable failure) {

    // An adapter tells the store which failures are worth repeating. This
    // double reports exactly one kind as permanent, so a test can prove that such an
    // entry is blocked immediately instead of after the configured attempts
    var candidate = failure;
    while (candidate != null) {
      if (candidate instanceof DummyPermanentFailure) {
        return false;
      }
      candidate = candidate.getCause() == candidate
          ? null
          : candidate.getCause();
    }
    return true;

  }

  /**
   * Whether this dummy stands in for a BPMS repeating a task it did not learn the
   * outcome of - switched on by <code>dummy-adapter.at-least-once-delivery</code>.
   */
  @Override
  public boolean deliversTasksAtLeastOnce() {

    return deliversTasksAtLeastOnce;

  }

  /**
   * Loads the workflow aggregate the way a remote BPMS adapter does in phase two: it
   * builds the variables it sends to the BPMS from the aggregate, so it calls the
   * application's persistence from the outbox dispatcher's thread. Switched on by
   * <code>dummy-adapter.read-aggregate-in-phase-two</code> (the tests of the
   * phase-two contract); off by default because most test doubles of
   * {@link AggregatePersistenceAware} implement nothing but save.
   *
   * @param aggregatePersistence The application's persistence of this aggregate
   * @param workflowAggregateId The aggregate's ID
   */
  private void readAggregateLikeARemoteBpms(
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    if (!readsAggregateInPhaseTwo) {
      return;
    }
    aggregatePersistence.loadById(workflowAggregateId);

  }

  @Override
  public void startWorkflowPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate) {

  }

  @Override
  public void startWorkflowPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    readAggregateLikeARemoteBpms(aggregatePersistence, workflowAggregateId);

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.startedWorkflowPhaseTwo(adapterId, workflowAggregateId));
    }

  }

  @Override
  public void completeTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId) {

  }

  @Override
  public void completeTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    readAggregateLikeARemoteBpms(aggregatePersistence, workflowAggregateId);

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.completedTaskPhaseTwo(adapterId, workflowAggregateId, taskId));
    }

  }

  @Override
  public void cancelTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

  }

  @Override
  public void cancelTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    readAggregateLikeARemoteBpms(aggregatePersistence, workflowAggregateId);

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.canceledTaskPhaseTwo(workflowAggregateId, taskId, bpmnErrorCode));
    }

  }

  @Override
  public void completeUserTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId) {

  }

  @Override
  public void completeUserTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    readAggregateLikeARemoteBpms(aggregatePersistence, workflowAggregateId);

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.completedUserTaskPhaseTwo(workflowAggregateId, taskId));
    }

  }

  @Override
  public void cancelUserTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId,
      final String bpmnErrorCode) {

  }

  @Override
  public void cancelUserTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    readAggregateLikeARemoteBpms(aggregatePersistence, workflowAggregateId);

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.canceledUserTaskPhaseTwo(workflowAggregateId, taskId, bpmnErrorCode));
    }

  }

  @Override
  public void sendSignalPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String signalName) {

  }

  @Override
  public void sendSignalPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String signalName) {

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.broadcastSignal(signalName, true));
    }

  }

  @Override
  public void aggregateChangedPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId) {

  }

  @Override
  public void aggregateChangedPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    readAggregateLikeARemoteBpms(aggregatePersistence, workflowAggregateId);

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.aggregateChanged(workflowAggregateId, taskId, true));
    }

  }

  @Override
  public void correlateMessagePhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String messageName,
      final String correlationId) {

  }

  @Override
  public void correlateMessagePhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String messageName,
      final String correlationId) {

    readAggregateLikeARemoteBpms(aggregatePersistence, workflowAggregateId);

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.correlatedMessagePhaseTwo(workflowAggregateId, messageName, correlationId));
    }

  }

  @Override
  public void startWorkflowByMessagePhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String messageName) {

  }

  @Override
  public void startWorkflowByMessagePhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String messageName) {

    readAggregateLikeARemoteBpms(aggregatePersistence, workflowAggregateId);

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.startedWorkflowByMessagePhaseTwo(workflowAggregateId, messageName));
    }

  }


  @Override
  public java.util.List<io.vanillabp.spi.process.ProcessDefinition> getProcessDefinitions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String historyContext) {

    if (viewerSources == null) {
      return java.util.List.of();
    }
    return viewerSources
        .stream()
        .map(source -> source.getProcessDefinitions(adapterId, workflowAggregateId, historyContext))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElseGet(java.util.List::of);

  }

  @Override
  public java.io.InputStream getBpmnXml(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String processDefinitionId) {

    if (viewerSources == null) {
      return null;
    }
    return viewerSources
        .stream()
        .map(source -> source.getBpmnXml(adapterId, processDefinitionId))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .map(xml -> (java.io.InputStream) new java.io.ByteArrayInputStream(
            xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
        .orElse(null);

  }

  @Override
  public io.vanillabp.spi.process.WorkflowHistory getWorkflowHistory(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String historyContext) {

    if (viewerSources == null) {
      return null;
    }
    return viewerSources
        .stream()
        .map(source -> source.getWorkflowHistory(adapterId, workflowAggregateId, historyContext))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);

  }

}
