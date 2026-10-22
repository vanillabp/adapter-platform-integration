package io.vanillabp.bpmsdouble;

import java.util.Map;

import io.vanillabp.integration.adapter.spi.PhaseOneRequest;
import io.vanillabp.integration.adapter.spi.PhaseOperationHandler;
import io.vanillabp.integration.adapter.spi.PhaseTwoRequest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.spi.AggregatePersistenceAware;
import io.vanillabp.integration.spi.PhaseOperation;
import lombok.extern.slf4j.Slf4j;

/**
 * The process service of the double. It logs what it was asked and tells the hooks a
 * test declared. It changes nothing anywhere. There is one instance per configured
 * adapter id of the type {@link DummyAdapter#ADAPTER_TYPE}. The adapter id is a
 * constructor parameter, so a test can play a migration between two of them.
 * <p>
 * {@link DummyPhaseTwoListener} beans are notified in phase two. The properties of
 * {@link DummyAdapter} switch on two behaviours which a real BPMS has and a logging
 * double would not: repeating a task whose outcome it never learned, and reading the
 * aggregate while phase two runs.
 *
 * @param <A> The workflow aggregate's type, as the application declared it
 */
@Slf4j
public class DummyProcessService<A> implements io.vanillabp.integration.adapter.spi.MigratableProcessService<A> {

  private final String adapterId;

  private final boolean deliversTasksAtLeastOnce;

  private final boolean readsAggregateInPhaseTwo;

  private final HookBeans<DummyPhaseTwoListener> phaseTwoListeners;

  private final HookBeans<DummyTaskAwarenessSource> taskAwarenessSources;

  private final HookBeans<DummyViewerSource> viewerSources;

  /**
   * Builds the process service of one configured adapter id. Each platform module
   * calls this once per id. So a test which configures two ids can move a workflow
   * from one to the other.
   *
   * @param adapterId The id this instance answers for
   * @param deliversTasksAtLeastOnce See {@link DummyAdapter#PROPERTY_AT_LEAST_ONCE_DELIVERY}
   * @param readsAggregateInPhaseTwo See {@link DummyAdapter#PROPERTY_READ_AGGREGATE_IN_PHASE_TWO}
   * @param phaseTwoListeners The hooks watching phase two
   * @param taskAwarenessSources The hooks answering what this BPMS knows about
   * @param viewerSources The hooks answering the viewer API
   */
  public DummyProcessService(
      final String adapterId,
      final boolean deliversTasksAtLeastOnce,
      final boolean readsAggregateInPhaseTwo,
      final HookBeans<DummyPhaseTwoListener> phaseTwoListeners,
      final HookBeans<DummyTaskAwarenessSource> taskAwarenessSources,
      final HookBeans<DummyViewerSource> viewerSources) {

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

  /**
   * What the double does for each operation: it logs, and it tells the listeners a test
   * registered. Phase one changes nothing. A test watches phase two.
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

    // Tests steer the answer with DummyTaskAwarenessSource beans. Without one the double
    // knows no task.
    return taskAwarenessSources
        .all()
        .map(source -> source.awarenessOfTask(adapterId, workflowAggregateId, taskId))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(WorkflowAwareness.UNKNOWN_TO_BPMS);

  }

  @Override
  public WorkflowAwareness awarenessOfUserTask(
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
      final Object workflowAggregateId,
      final String taskId) {

    return taskAwarenessSources
        .all()
        .map(source -> source.awarenessOfUserTask(adapterId, workflowAggregateId, taskId))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(WorkflowAwareness.UNKNOWN_TO_BPMS);

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

    return taskAwarenessSources
        .all()
        .map(source -> source.awarenessOfWorkflow(adapterId, workflowAggregateId))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(WorkflowAwareness.UNKNOWN_TO_BPMS);

  }

  /**
   * The same question with the BPMS' own id of the workflow. The election passes it where
   * VanillaBP holds one. The double hands it to its hooks, so a test can show that a fake
   * adapter really sees it.
   */
  @Override
  public WorkflowAwareness awarenessOfWorkflow(
      final io.vanillabp.integration.adapter.spi.WorkflowScope scope,
      final io.vanillabp.integration.spi.AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String workflowId) {

    log
        .info(
            "Dummy-Adapter[{}]: Checking awareness of workflow '{}' of workflow aggregate '{}'",
            adapterId,
            workflowId,
            workflowAggregateId);

    return taskAwarenessSources
        .all()
        .map(source -> source.awarenessOfWorkflow(adapterId, workflowAggregateId, workflowId))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(WorkflowAwareness.UNKNOWN_TO_BPMS);

  }

  /**
   * Whether the double plays a BPMS which can answer whether it holds a workflow. A test
   * steers this through {@link DummyTaskAwarenessSource}. This is how a test makes the
   * core refuse to combine a guessing adapter with a second one.
   */
  @Override
  public boolean canLocateWorkflows() {

    return taskAwarenessSources
        .all()
        .allMatch(source -> source.canLocateWorkflows(adapterId));

  }

  @Override
  public io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay workflowVisibilityDelay() {

    return taskAwarenessSources
        .all()
        .map(source -> source.workflowVisibilityDelay(adapterId))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElseGet(io.vanillabp.integration.adapter.spi.WorkflowVisibilityDelay::none);

  }

  @Override
  public boolean isPhaseTwoFailureRepeatable(
      final Throwable failure) {

    // An adapter tells the store which failures are worth repeating. This double reports
    // exactly one kind as permanent. So a test can prove that such an entry is blocked at
    // once, not after the configured attempts.
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
   * Whether the double plays a BPMS which repeats a task when it did not learn its
   * outcome. Switched on by {@link DummyAdapter#PROPERTY_AT_LEAST_ONCE_DELIVERY}.
   */
  @Override
  public boolean deliversTasksAtLeastOnce() {

    return deliversTasksAtLeastOnce;

  }

  /**
   * Loads the workflow aggregate the way an adapter of a remote BPMS does in phase two.
   * Such an adapter builds the variables it sends from the aggregate. So it calls the
   * application's persistence from the thread of the outbox dispatcher. Switched on by
   * {@link DummyAdapter#PROPERTY_READ_AGGREGATE_IN_PHASE_TWO}. It is off by default,
   * because most test doubles of {@link AggregatePersistenceAware} implement only save.
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

    readAggregateLikeARemoteBpms(request.aggregatePersistence(), request.workflowAggregateId());

    phaseTwoListeners
        .all()
        .forEach(listener -> listener.startedWorkflowPhaseTwo(adapterId, request.workflowAggregateId()));

    // a real adapter learns the id of the instance it created, and says so
    final var workflowId = workflowIdOf(request.workflowAggregateId());
    if (workflowId != null) {
      request.reportStartedWorkflow(workflowId);
    }

  }

  /**
   * The id which the first listener knows for the workflow of the aggregate, or
   * <code>null</code> where none knows one.
   */
  private String workflowIdOf(
      final Object workflowAggregateId) {

    return phaseTwoListeners
        .all()
        .map(listener -> listener.workflowIdOf(adapterId, workflowAggregateId))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);

  }

  /**
   * Where VanillaBP handed over no id of the workflow, a real adapter searches for it. The
   * double says what a listener knows instead, as if its search had found that.
   */
  private void reportTheWorkflowItFound(
      final PhaseTwoRequest<A> request) {

    if (request.workflowId() != null) {
      return;
    }
    final var workflowId = workflowIdOf(request.workflowAggregateId());
    if (workflowId != null) {
      request.reportFoundWorkflow(workflowId);
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

    readAggregateLikeARemoteBpms(request.aggregatePersistence(), request.workflowAggregateId());

    phaseTwoListeners
        .all()
        .forEach(
            listener -> listener
                .completedTaskPhaseTwo(adapterId, request.workflowAggregateId(), request.taskId()));

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

    readAggregateLikeARemoteBpms(request.aggregatePersistence(), request.workflowAggregateId());

    phaseTwoListeners
        .all()
        .forEach(listener -> listener.canceledTaskPhaseTwo(request.workflowAggregateId(), request.taskId(),
            request.bpmnErrorCode()));

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

    readAggregateLikeARemoteBpms(request.aggregatePersistence(), request.workflowAggregateId());

    phaseTwoListeners
        .all()
        .forEach(listener -> listener.completedUserTaskPhaseTwo(request.workflowAggregateId(), request.taskId()));

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

    readAggregateLikeARemoteBpms(request.aggregatePersistence(), request.workflowAggregateId());

    phaseTwoListeners
        .all()
        .forEach(listener -> listener.canceledUserTaskPhaseTwo(request.workflowAggregateId(), request.taskId(),
            request.bpmnErrorCode()));

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

    phaseTwoListeners
        .all()
        .forEach(listener -> listener.broadcastSignal(request.signalName(), true));

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

    readAggregateLikeARemoteBpms(request.aggregatePersistence(), request.workflowAggregateId());

    phaseTwoListeners
        .all()
        .forEach(listener -> listener.aggregateChanged(request.workflowAggregateId(), request.taskId(), true));

    reportTheWorkflowItFound(request);

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

    readAggregateLikeARemoteBpms(request.aggregatePersistence(), request.workflowAggregateId());

    phaseTwoListeners
        .all()
        .forEach(listener -> listener.correlatedMessagePhaseTwo(request.workflowAggregateId(), request.messageName(),
            request.correlationId()));

    reportTheWorkflowItFound(request);

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

    readAggregateLikeARemoteBpms(request.aggregatePersistence(), request.workflowAggregateId());

    phaseTwoListeners
        .all()
        .forEach(listener -> listener.startedWorkflowByMessagePhaseTwo(request.workflowAggregateId(),
            request.messageName()));

  }

  @Override
  public java.util.List<io.vanillabp.spi.process.ProcessDefinition> getProcessDefinitions(
      final String workflowModuleId,
      final String bpmnProcessId,
      final AggregatePersistenceAware<A> aggregatePersistence,
      final Object workflowAggregateId,
      final String historyContext) {

    return viewerSources
        .all()
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

    return viewerSources
        .all()
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

    return viewerSources
        .all()
        .map(source -> source.getWorkflowHistory(adapterId, workflowAggregateId, historyContext))
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(null);

  }

}
