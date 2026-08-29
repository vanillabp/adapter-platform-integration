package io.vanillabp.adapter.dummy.springboot.processservice;

import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;

import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Log-only process service of the dummy adapter. One instance exists per configured
 * adapter id of the dummy type - the adapter id is a CONSTRUCTOR parameter (see
 * {@link io.vanillabp.adapter.dummy.springboot.DummyAdapterBeanRegistrar}).
 * <p>
 * Optional {@link DummyAdapterPhaseTwoListener} beans are notified on phase two. The
 * property <code>dummy-adapter.at-least-once-delivery</code> makes this dummy report
 * the delivery behaviour of a BPMS which repeats a task it did not learn the outcome
 * of ({@link #deliversTasksAtLeastOnce()}).
 */
@Slf4j
@RequiredArgsConstructor
public class MigratableProcessService<A> implements io.vanillabp.integration.adapter.spi.MigratableProcessService<A> {

  private final String adapterId;

  private final boolean deliversTasksAtLeastOnce;

  private final ObjectProvider<DummyAdapterPhaseTwoListener> phaseTwoListeners;

  private final ObjectProvider<DummyTaskAwarenessSource> taskAwarenessSources;

  private final ObjectProvider<DummyViewerSource> viewerSources;

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  /**
   * What this dummy does for each operation: it logs, and it tells the listeners a test
   * registered. Phase one changes nothing, phase two is what a test watches.
   */
  @Override
  public Map<PhaseOperation, PhaseOperationHandler<A>> phaseOperations() {

    return Map
        .ofEntries(
            Map
                .entry(
                    PhaseOperation.START_WORKFLOW,
                    PhaseOperationHandler.of(this::preflightStart, this::startWorkflow)),
            Map
                .entry(
                    PhaseOperation.START_WORKFLOW_BY_MESSAGE,
                    PhaseOperationHandler.of(this::preflightStartByMessage, this::startWorkflowByMessage)),
            Map
                .entry(
                    PhaseOperation.COMPLETE_TASK,
                    PhaseOperationHandler.of(this::preflightCompleteTask, this::completeTask)),
            Map
                .entry(
                    PhaseOperation.CANCEL_TASK,
                    PhaseOperationHandler.of(this::preflightCancelTask, this::cancelTask)),
            Map
                .entry(
                    PhaseOperation.COMPLETE_USER_TASK,
                    PhaseOperationHandler.of(this::preflightCompleteUserTask, this::completeUserTask)),
            Map
                .entry(
                    PhaseOperation.CANCEL_USER_TASK,
                    PhaseOperationHandler.of(this::preflightCancelUserTask, this::cancelUserTask)),
            Map
                .entry(
                    PhaseOperation.CORRELATE_MESSAGE,
                    PhaseOperationHandler.of(this::preflightCorrelateMessage, this::correlateMessage)),
            Map
                .entry(
                    PhaseOperation.SEND_SIGNAL,
                    PhaseOperationHandler.of(this::preflightSendSignal, this::sendSignal)),
            Map
                .entry(
                    PhaseOperation.AGGREGATE_CHANGED,
                    PhaseOperationHandler.of(this::preflightAggregateChanged, this::pushChangedAggregate)));

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


  /**
   * Whether this dummy stands in for a BPMS which can be asked whether it holds a
   * workflow - a test steers it through {@link DummyTaskAwarenessSource}, which is how
   * the core's refusal to combine a guessing adapter with a second one is exercised.
   */
  @Override
  public boolean canLocateWorkflows() {

    if (taskAwarenessSources == null) {
      return true;
    }
    return taskAwarenessSources
        .stream()
        .allMatch(source -> source.canLocateWorkflows(adapterId));

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

  /**
   * Whether this dummy stands in for a BPMS repeating a task it did not learn the
   * outcome of - switched on by <code>dummy-adapter.at-least-once-delivery</code>.
   */
  @Override
  public boolean deliversTasksAtLeastOnce() {

    return deliversTasksAtLeastOnce;

  }

  private void preflightStart(
      final PhaseOneRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Starting workflow (phase one) of BPMN process '{}' of workflow module '{}'",
        adapterId,
        request.bpmnProcessId(),
        request.workflowModuleId());

  }

  private void startWorkflow(
      final PhaseTwoRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Starting workflow (phase two) of BPMN process '{}' of workflow module '{}' for aggregate '{}'",
        adapterId,
        request.bpmnProcessId(),
        request.workflowModuleId(),
        request.workflowAggregateId());

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.startedWorkflowPhaseTwo(request.workflowAggregateId()));
    }

  }

  private void preflightCompleteTask(
      final PhaseOneRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Completing task '{}' (phase one) of BPMN process '{}' of workflow module '{}'",
        adapterId,
        request.taskId(),
        request.bpmnProcessId(),
        request.workflowModuleId());

  }

  private void completeTask(
      final PhaseTwoRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Completing task '{}' (phase two) of BPMN process '{}' of workflow module '{}' for "
            + "aggregate '{}'",
        adapterId,
        request.taskId(),
        request.bpmnProcessId(),
        request.workflowModuleId(),
        request.workflowAggregateId());

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.completedTaskPhaseTwo(request.workflowAggregateId(), request.taskId()));
    }

  }

  private void preflightCancelTask(
      final PhaseOneRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Canceling task '{}' (phase one, error code '{}') of BPMN process '{}' of workflow "
            + "module '{}'",
        adapterId,
        request.taskId(),
        request.bpmnErrorCode(),
        request.bpmnProcessId(),
        request.workflowModuleId());

  }

  private void cancelTask(
      final PhaseTwoRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Canceling task '{}' (phase two, error code '{}') of BPMN process '{}' of workflow "
            + "module '{}' for aggregate '{}'",
        adapterId,
        request.taskId(),
        request.bpmnErrorCode(),
        request.bpmnProcessId(),
        request.workflowModuleId(),
        request.workflowAggregateId());

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.canceledTaskPhaseTwo(request.workflowAggregateId(), request.taskId(),
              request.bpmnErrorCode()));
    }

  }

  private void preflightCompleteUserTask(
      final PhaseOneRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Completing user task '{}' (phase one) of BPMN process '{}' of workflow module '{}'",
        adapterId,
        request.taskId(),
        request.bpmnProcessId(),
        request.workflowModuleId());

  }

  private void completeUserTask(
      final PhaseTwoRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Completing user task '{}' (phase two) of BPMN process '{}' of workflow module '{}' "
            + "for aggregate '{}'",
        adapterId,
        request.taskId(),
        request.bpmnProcessId(),
        request.workflowModuleId(),
        request.workflowAggregateId());

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.completedUserTaskPhaseTwo(request.workflowAggregateId(), request.taskId()));
    }

  }

  private void preflightCancelUserTask(
      final PhaseOneRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Canceling user task '{}' (phase one, error code '{}') of BPMN process '{}' of "
            + "workflow module '{}'",
        adapterId,
        request.taskId(),
        request.bpmnErrorCode(),
        request.bpmnProcessId(),
        request.workflowModuleId());

  }

  private void cancelUserTask(
      final PhaseTwoRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Canceling user task '{}' (phase two, error code '{}') of BPMN process '{}' of "
            + "workflow module '{}' for aggregate '{}'",
        adapterId,
        request.taskId(),
        request.bpmnErrorCode(),
        request.bpmnProcessId(),
        request.workflowModuleId(),
        request.workflowAggregateId());

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.canceledUserTaskPhaseTwo(request.workflowAggregateId(), request.taskId(),
              request.bpmnErrorCode()));
    }

  }

  private void preflightSendSignal(
      final PhaseOneRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Broadcasting signal '{}' (phase one) of workflow module '{}'",
        adapterId,
        request.signalName(),
        request.workflowModuleId());

  }

  private void sendSignal(
      final PhaseTwoRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Broadcasting signal '{}' (phase two) of workflow module '{}'",
        adapterId,
        request.signalName(),
        request.workflowModuleId());

    if (phaseTwoListeners != null) {
      phaseTwoListeners.forEach(listener -> listener.broadcastSignal(request.signalName(), true));
    }

  }

  private void preflightAggregateChanged(
      final PhaseOneRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Pushing the changed aggregate (phase one, task '{}') of BPMN process '{}' of "
            + "workflow module '{}'",
        adapterId,
        request.taskId(),
        request.bpmnProcessId(),
        request.workflowModuleId());

  }

  private void pushChangedAggregate(
      final PhaseTwoRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Pushing the changed aggregate (phase two, task '{}') of BPMN process '{}' of "
            + "workflow module '{}' for aggregate '{}'",
        adapterId,
        request.taskId(),
        request.bpmnProcessId(),
        request.workflowModuleId(),
        request.workflowAggregateId());

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .forEach(listener -> listener.aggregateChanged(request.workflowAggregateId(), request.taskId(), true));
    }

  }

  private void preflightCorrelateMessage(
      final PhaseOneRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Correlating message '{}' (phase one, correlation id '{}') of BPMN process '{}' of "
            + "workflow module '{}'",
        adapterId,
        request.messageName(),
        request.correlationId(),
        request.bpmnProcessId(),
        request.workflowModuleId());

  }

  private void correlateMessage(
      final PhaseTwoRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Correlating message '{}' (phase two, correlation id '{}') of BPMN process '{}' of "
            + "workflow module '{}' for aggregate '{}'",
        adapterId,
        request.messageName(),
        request.correlationId(),
        request.bpmnProcessId(),
        request.workflowModuleId(),
        request.workflowAggregateId());

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.correlatedMessagePhaseTwo(request.workflowAggregateId(), request.messageName(),
              request.correlationId()));
    }

  }

  private void preflightStartByMessage(
      final PhaseOneRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Starting workflow by message '{}' (phase one) of BPMN process '{}' of workflow "
            + "module '{}'",
        adapterId,
        request.messageName(),
        request.bpmnProcessId(),
        request.workflowModuleId());

  }

  private void startWorkflowByMessage(
      final PhaseTwoRequest<A> request) {

    log.info(
        "Dummy-Adapter[{}]: Starting workflow by message '{}' (phase two) of BPMN process '{}' of workflow "
            + "module '{}' for aggregate '{}'",
        adapterId,
        request.messageName(),
        request.bpmnProcessId(),
        request.workflowModuleId(),
        request.workflowAggregateId());

    if (phaseTwoListeners != null) {
      phaseTwoListeners
          .stream()
          .forEach(listener -> listener.startedWorkflowByMessagePhaseTwo(request.workflowAggregateId(),
              request.messageName()));
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
