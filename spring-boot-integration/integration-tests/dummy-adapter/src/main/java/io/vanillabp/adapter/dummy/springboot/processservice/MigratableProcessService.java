package io.vanillabp.adapter.dummy.springboot.processservice;

import org.springframework.beans.factory.ObjectProvider;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Log-only process service of the dummy adapter. One instance exists per configured
 * adapter id of the dummy type - the adapter id is a CONSTRUCTOR parameter (see
 * {@link io.vanillabp.adapter.dummy.springboot.DummyAdapterBeanRegistrar}).
 * <p>
 * For testing the two-phase workflow start, the property
 * <code>dummy-adapter.two-phase-commit</code> forces
 * {@link #needsTwoPhaseCommitForStartingWorkflows()} to return <code>true</code>, and
 * optional {@link DummyAdapterPhaseTwoListener} beans are notified on phase two.
 */
@Slf4j
@RequiredArgsConstructor
public class MigratableProcessService<A> implements io.vanillabp.integration.adapter.spi.MigratableProcessService<A> {

  private final String adapterId;

  private final boolean needsTwoPhaseCommitForStartingWorkflows;

  private final ObjectProvider<DummyAdapterPhaseTwoListener> phaseTwoListeners;

  private final ObjectProvider<DummyTaskAwarenessSource> taskAwarenessSources;

  private final ObjectProvider<DummyViewerSource> viewerSources;

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  @Override
  public WorkflowAwareness awarenessOfTask(
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
      final Object workflowAggregateId,
      final String taskId) {

    log.info("Dummy-Adapter[{}]: Checking awareness of task '{}' of workflow aggregate '{}'", adapterId, taskId,
        workflowAggregateId);

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

    log.info(
        "Dummy-Adapter[{}]: Checking awareness of workflow of workflow aggregate '{}'",
        adapterId,
        workflowAggregateId);

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
  public boolean needsTwoPhaseCommitForStartingWorkflows() {

    return needsTwoPhaseCommitForStartingWorkflows;

  }

  /**
   * A dummy configured as a remote BPMS (two-phase commit) also stands in for its
   * at-least-once task delivery: the outcome is reported after the local commit, so the
   * same task may arrive again (story 51).
   */
  @Override
  public boolean deliversTasksAtLeastOnce() {

    return needsTwoPhaseCommitForStartingWorkflows;

  }

  @Override
  public void startWorkflowPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate) {

    log.info(
        "Dummy-Adapter[{}]: Starting workflow (phase one) of BPMN process '{}' of workflow module '{}'",
        adapterId,
        bpmnProcessId,
        workflowModuleId);

  }

  @Override
  public void startWorkflowPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId) {

    log.info(
        "Dummy-Adapter[{}]: Starting workflow (phase two) of BPMN process '{}' of workflow module '{}' for aggregate '{}'",
        adapterId,
        bpmnProcessId,
        workflowModuleId,
        workflowAggregateId);

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.startedWorkflowPhaseTwo(workflowAggregateId));
    }

  }

  @Override
  public void completeTaskPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId) {

    log.info(
        "Dummy-Adapter[{}]: Completing task '{}' (phase one) of BPMN process '{}' of workflow module '{}'",
        adapterId,
        taskId,
        bpmnProcessId,
        workflowModuleId);

  }

  @Override
  public void completeTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    log.info(
        "Dummy-Adapter[{}]: Completing task '{}' (phase two) of BPMN process '{}' of workflow module '{}' for "
            + "aggregate '{}'",
        adapterId,
        taskId,
        bpmnProcessId,
        workflowModuleId,
        workflowAggregateId);

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.completedTaskPhaseTwo(workflowAggregateId, taskId));
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

    log.info(
        "Dummy-Adapter[{}]: Canceling task '{}' (phase one, error code '{}') of BPMN process '{}' of workflow "
            + "module '{}'",
        adapterId,
        taskId,
        bpmnErrorCode,
        bpmnProcessId,
        workflowModuleId);

  }

  @Override
  public void cancelTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    log.info(
        "Dummy-Adapter[{}]: Canceling task '{}' (phase two, error code '{}') of BPMN process '{}' of workflow "
            + "module '{}' for aggregate '{}'",
        adapterId,
        taskId,
        bpmnErrorCode,
        bpmnProcessId,
        workflowModuleId,
        workflowAggregateId);

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

    log.info(
        "Dummy-Adapter[{}]: Completing user task '{}' (phase one) of BPMN process '{}' of workflow module '{}'",
        adapterId,
        taskId,
        bpmnProcessId,
        workflowModuleId);

  }

  @Override
  public void completeUserTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    log.info(
        "Dummy-Adapter[{}]: Completing user task '{}' (phase two) of BPMN process '{}' of workflow module '{}' "
            + "for aggregate '{}'",
        adapterId,
        taskId,
        bpmnProcessId,
        workflowModuleId,
        workflowAggregateId);

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

    log.info(
        "Dummy-Adapter[{}]: Canceling user task '{}' (phase one, error code '{}') of BPMN process '{}' of "
            + "workflow module '{}'",
        adapterId,
        taskId,
        bpmnErrorCode,
        bpmnProcessId,
        workflowModuleId);

  }

  @Override
  public void cancelUserTaskPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId,
      final String bpmnErrorCode) {

    log.info(
        "Dummy-Adapter[{}]: Canceling user task '{}' (phase two, error code '{}') of BPMN process '{}' of "
            + "workflow module '{}' for aggregate '{}'",
        adapterId,
        taskId,
        bpmnErrorCode,
        bpmnProcessId,
        workflowModuleId,
        workflowAggregateId);

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

    log.info(
        "Dummy-Adapter[{}]: Broadcasting signal '{}' (phase one) of workflow module '{}'",
        adapterId,
        signalName,
        workflowModuleId);

    // like an embedded BPMS: without a two-phase commit the broadcast happens here
    if (!needsTwoPhaseCommitForStartingWorkflows && (phaseTwoListeners != null)) {
      phaseTwoListeners.forEach(listener -> listener.broadcastSignal(signalName, false));
    }

  }

  @Override
  public void sendSignalPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final String signalName) {

    log.info(
        "Dummy-Adapter[{}]: Broadcasting signal '{}' (phase two) of workflow module '{}'",
        adapterId,
        signalName,
        workflowModuleId);

    if (phaseTwoListeners != null) {
      phaseTwoListeners.forEach(listener -> listener.broadcastSignal(signalName, true));
    }

  }

  @Override
  public void aggregateChangedPhaseOne(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final A workflowAggregate,
      final String taskId) {

    log.info(
        "Dummy-Adapter[{}]: Pushing the changed aggregate (phase one, task '{}') of BPMN process '{}' of "
            + "workflow module '{}'",
        adapterId,
        taskId,
        bpmnProcessId,
        workflowModuleId);

    // like an embedded BPMS: without a two-phase commit the push happens here
    if (!needsTwoPhaseCommitForStartingWorkflows && (phaseTwoListeners != null)) {
      final var aggregateId = aggregatePersistence.getAggregateId(workflowAggregate);
      phaseTwoListeners.forEach(listener -> listener.aggregateChanged(aggregateId, taskId, false));
    }

  }

  @Override
  public void aggregateChangedPhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String taskId) {

    log.info(
        "Dummy-Adapter[{}]: Pushing the changed aggregate (phase two, task '{}') of BPMN process '{}' of "
            + "workflow module '{}' for aggregate '{}'",
        adapterId,
        taskId,
        bpmnProcessId,
        workflowModuleId,
        workflowAggregateId);

    if (phaseTwoListeners != null) {
      phaseTwoListeners.forEach(listener -> listener.aggregateChanged(workflowAggregateId, taskId, true));
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

    log.info(
        "Dummy-Adapter[{}]: Correlating message '{}' (phase one, correlation id '{}') of BPMN process '{}' of "
            + "workflow module '{}'",
        adapterId,
        messageName,
        correlationId,
        bpmnProcessId,
        workflowModuleId);

  }

  @Override
  public void correlateMessagePhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String messageName,
      final String correlationId) {

    log.info(
        "Dummy-Adapter[{}]: Correlating message '{}' (phase two, correlation id '{}') of BPMN process '{}' of "
            + "workflow module '{}' for aggregate '{}'",
        adapterId,
        messageName,
        correlationId,
        bpmnProcessId,
        workflowModuleId,
        workflowAggregateId);

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

    log.info(
        "Dummy-Adapter[{}]: Starting workflow by message '{}' (phase one) of BPMN process '{}' of workflow "
            + "module '{}'",
        adapterId,
        messageName,
        bpmnProcessId,
        workflowModuleId);

  }

  @Override
  public void startWorkflowByMessagePhaseTwo(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String messageName) {

    log.info(
        "Dummy-Adapter[{}]: Starting workflow by message '{}' (phase two) of BPMN process '{}' of workflow "
            + "module '{}' for aggregate '{}'",
        adapterId,
        messageName,
        bpmnProcessId,
        workflowModuleId,
        workflowAggregateId);

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
