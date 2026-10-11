package io.vanillabp.bpmsdouble;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.DmnDecisionIds;
import io.vanillabp.integration.adapter.spi.workflowend.WorkflowEndedContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartResult;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;

/**
 * The double's deployment service. There is one instance per configured adapter id. It
 * does not parse BPMN. The model and the processing context are plain {@link Object}s.
 * The BPMN process id is the filename below the module's BPMN location, without the
 * <code>.bpmn</code> extension. So two files of one workflow module stay apart, even in
 * different subdirectories. Every pipeline call is logged and offered to
 * {@link DummyDeploymentListener} beans. This is how a test checks the order of the
 * pipeline and how it makes a call fail.
 * <p>
 * What a test may rely on here is written down in the readme of the
 * <code>bpms-double</code> module.
 */
public class DummyDeploymentService implements AdapterDeploymentService<Object, Object> {

  private static final Logger log = LoggerFactory.getLogger(DummyDeploymentService.class);

  private final String adapterId;

  private final HookBeans<DummyDeploymentListener> listeners;

  /**
   * Everything the platform hands over, in one object. An adapter with an incomplete
   * registration is never created (see
   * {@link io.vanillabp.integration.adapter.spi.AdapterCollaborators}).
   */
  private final io.vanillabp.integration.adapter.spi.AdapterCollaborators collaborators;

  /**
   * Test hook standing in for the BPMN model (see {@link DummyTaskWiringSource}).
   */
  private final HookBeans<DummyTaskWiringSource> taskWiringSource;

  /**
   * Test hook standing in for the start events of the BPMN model (see
   * {@link DummyBpmsInitiatedStartSource}).
   */
  private final HookBeans<DummyBpmsInitiatedStartSource> bpmsInitiatedStartSource;

  /**
   * Test hook standing in for the versions the BPMS deployed (see
   * {@link DummyProcessVersionSource}).
   */
  private final HookBeans<DummyProcessVersionSource> processVersionSource;

  /**
   * Test hook standing in for what a real adapter asks its BPMS (see
   * {@link DummyHealthSource}).
   */
  private final HookBeans<DummyHealthSource> healthSource;

  @Override
  public io.vanillabp.integration.adapter.spi.health.AdapterHealth checkHealth() {

    return healthSource
        .first()
        .map(source -> source.healthOf(adapterId))
        .orElse(null);

  }

  /**
   * The versions of the deployed BPMN processes, cached the way a real adapter caches
   * them. The test's {@link DummyProcessVersionSource} plays the BPMS query.
   */
  private final io.vanillabp.integration.adapter.spi.version.CachingProcessVersionCatalog processVersions = new io.vanillabp.integration.adapter.spi.version.CachingProcessVersionCatalog(
      java.time.Duration.ZERO) {

    @Override
    protected java.util.List<io.vanillabp.integration.adapter.spi.version.DeployedProcessVersion> fetchDeployedVersions(
        final String workflowModuleId,
        final String bpmnProcessId) {

      return processVersionSource
          .first()
          .map(source -> source.versionsOf(adapterId, workflowModuleId, bpmnProcessId))
          .orElseGet(java.util.List::of);

    }

  };

  /**
   * The BPMN processes which got an end listener. A real adapter changes its model at
   * this point. The double only records the decision, so a test can check that a process
   * without a handler pays nothing.
   */
  private final java.util.List<String> processesWithEndListener = new java.util.concurrent.CopyOnWriteArrayList<>();

  /**
   * The BPMN processes this instance attached an end listener to, in the order they were
   * wired. A test reads it to show that a process without an end handler in the
   * application is left alone. A real adapter saves this change of its model there.
   *
   * @return A copy, so a test may keep it while the deployment goes on
   */
  public java.util.List<String> getProcessesWithEndListener() {

    return java.util.List.copyOf(processesWithEndListener);

  }

  /**
   * The BPMN processes which got a start listener. Only a process whose start is the start
   * of a workflow gets one. A called process does not.
   */
  private final java.util.List<String> processesWithStartListener = new java.util.concurrent.CopyOnWriteArrayList<>();

  /**
   * The BPMN processes this instance attached a start listener to, in the order they were
   * wired. A test reads it to show that a called process gets none.
   *
   * @return A copy, so a test may keep it while the deployment goes on
   */
  public java.util.List<String> getProcessesWithStartListener() {

    return java.util.List.copyOf(processesWithStartListener);

  }

  /**
   * Reports that a workflow ended, like a real adapter does from its process-end
   * listener. Triggered by integration tests.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param context The notification (as a real adapter would build it)
   */
  public void notifyWorkflowEnded(
      final String workflowModuleId,
      final String bpmnProcessId,
      final WorkflowEndedContext context) {

    log.info(
        "Dummy-Adapter[{}]: Workflow '{}' of {} ended ({})",
        adapterId,
        context.getWorkflowAggregateId(),
        workflowModuleId,
        context.getKind());

    // An adapter registered without this collaborator reports nothing. The build of the
    // collaborators already warned about that.
    collaborators
        .workflowEndedInvoker()
        .ifPresent(invoker -> invoker.workflowEnded(workflowModuleId, bpmnProcessId, context));

  }

  /**
   * Builds the deployment service of one configured adapter id. Each platform module
   * calls this once per id. So a test which configures two ids gets two doubles and can
   * play a migration between them.
   * <p>
   * Every collaborator arrives here, and none is set later. See decision 28 in the
   * repository's DECISIONS.md.
   *
   * @param adapterId The id this instance answers for
   * @param listeners The hooks watching the deployment pipeline
   * @param collaborators What the platform handed this adapter
   * @param taskWiringSource The hooks standing in for the BPMN model
   * @param bpmsInitiatedStartSource The hooks standing in for the model's start events
   * @param processVersionSource The hooks standing in for the versions the BPMS deployed
   * @param healthSource The hooks standing in for what the BPMS answers about itself
   */
  public DummyDeploymentService(
      final String adapterId,
      final HookBeans<DummyDeploymentListener> listeners,
      final io.vanillabp.integration.adapter.spi.AdapterCollaborators collaborators,
      final HookBeans<DummyTaskWiringSource> taskWiringSource,
      final HookBeans<DummyBpmsInitiatedStartSource> bpmsInitiatedStartSource,
      final HookBeans<DummyProcessVersionSource> processVersionSource,
      final HookBeans<DummyHealthSource> healthSource) {

    this.adapterId = adapterId;
    this.listeners = listeners;
    this.collaborators = collaborators;
    this.taskWiringSource = taskWiringSource;
    this.bpmsInitiatedStartSource = bpmsInitiatedStartSource;
    this.processVersionSource = processVersionSource;
    this.healthSource = healthSource;

  }

  /**
   * Reports a workflow the BPMS started on its own, like a real adapter does from a
   * process-start listener. Triggered by integration tests.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param context The notification (as a real adapter would build it)
   * @return The aggregate's ID and the variables to write back into the BPMS
   */
  public BpmsInitiatedStartResult startWorkflowByBpms(
      final String workflowModuleId,
      final String bpmnProcessId,
      final BpmsInitiatedStartContext context) {

    log.info(
        "Dummy-Adapter[{}]: The BPMS started '{}' of {} by start event '{}'",
        adapterId,
        bpmnProcessId,
        workflowModuleId,
        context.getStartEventId());

    return collaborators
        .bpmsInitiatedStartInvoker()
        .orElseThrow(
            () -> new IllegalStateException(
                "the dummy adapter '%s' was registered without a bpmsInitiatedStartInvoker".formatted(adapterId)))
        .startWorkflowByBpms(workflowModuleId, bpmnProcessId, context);

  }

  /**
   * Invokes a <code>&#64;WorkflowTask</code> method through the core, like a real
   * adapter does when its BPMS delivers a task. Triggered by integration tests.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param context The invocation context (as a real adapter would build it)
   * @return The outcome to be mapped to the BPMS
   */
  public WorkflowTaskOutcome invokeTask(
      final String workflowModuleId,
      final String bpmnProcessId,
      final TaskInvocationContext context) {

    log.info("Dummy-Adapter[{}]: Invoking task '{}' of {}", adapterId, context.getTaskDefinition(), workflowModuleId);

    return collaborators.workflowTaskInvoker().invokeWorkflowTask(workflowModuleId, bpmnProcessId, context);

  }

  /**
   * Asks the core to look at the other tasks it believes are open in the workflow of the
   * given delivery. An adapter of a remote BPMS does this after it handed a delivery
   * over. Triggered by integration tests, which bring their own probe.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param wakeUp The delivery which woke the application up
   * @param probe What answers whether one task still exists
   */
  public void reportTasksTheBpmsNoLongerHas(
      final String workflowModuleId,
      final String bpmnProcessId,
      final TaskInvocationContext wakeUp,
      final io.vanillabp.integration.adapter.spi.workflowtask.OpenTaskProbe probe) {

    log
        .info(
            "Dummy-Adapter[{}]: Looking at the other tasks open in workflow '{}' of {}",
            adapterId,
            wakeUp.getWorkflowId(),
            workflowModuleId);

    collaborators
        .workflowTaskInvoker()
        .reportTasksTheBpmsNoLongerHas(workflowModuleId, bpmnProcessId, wakeUp, probe);

  }

  @Override
  public Class<Object> getModelType() {

    return Object.class;

  }

  @Override
  public Class<Object> getProcessContextType() {

    return Object.class;

  }

  @Override
  public String getAdapterId() {

    return adapterId;

  }

  @Override
  public String getAdapterType() {

    return DummyAdapter.ADAPTER_TYPE;

  }

  private void notifyListeners(
      final String method,
      final String workflowModuleId,
      final String detail) {

    listeners
        .all()
        .forEach(listener -> listener.onPipelineCall(adapterId, method, workflowModuleId, detail));

  }

  @Override
  public List<Map.Entry<String, Object>> readBpmn(
      final String workflowModuleId,
      final String filename,
      final InputStream bpmn,
      final boolean isVanillaBpBpmn) throws BpmnParseException {

    log.info("Dummy-Adapter[{}]: Reading BPMN '{}' for {}", adapterId, filename, workflowModuleId);
    notifyListeners("readBpmn", workflowModuleId, filename);

    // A test may declare what the file holds. This is how a test models a file with a
    // SECOND executable process. Without that, each file holds one process, named after
    // the file.
    final var declaredProcesses = taskWiringSource
        .all()
        .map(source -> source.executableProcessesOf(adapterId, workflowModuleId, filename))
        .filter(processes -> !processes.isEmpty())
        .findFirst()
        .orElse(List.of());
    if (!declaredProcesses.isEmpty()) {
      return declaredProcesses
          .stream()
          .map(bpmnProcessId -> Map.entry(bpmnProcessId, new Object()))
          .toList();
    }
    final var bpmnProcessId = filename.endsWith(".bpmn")
        ? filename.substring(0, filename.length() - ".bpmn".length())
        : filename;
    return List.of(Map.entry(bpmnProcessId, new Object()));

  }

  @Override
  public Object readDmn(
      final String workflowModuleId,
      final Object existingContext,
      final String filename,
      final InputStream dmn) {

    log.info("Dummy-Adapter[{}]: Reading DMN '{}' for {}", adapterId, filename, workflowModuleId);
    notifyListeners("readDmn", workflowModuleId, filename);

    // Like a real adapter: the file is read as bytes and travels in the context which the
    // module's processes produced.
    DmnDecisionIds.bytesOf(dmn);
    return existingContext;

  }

  @Override
  public Object prepareBpmn(
      final String workflowModuleId,
      final Object existingContext,
      final String filename,
      final String bpmnProcessId,
      final Object model) {

    log.info("Dummy-Adapter[{}]: Preparing BPMN '{}' for {}", adapterId, filename, workflowModuleId);
    notifyListeners("prepareBpmn", workflowModuleId, filename);

    return existingContext != null
        ? existingContext
        : new Object();

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final Object model,
      final Object context) {

    log.info("Dummy-Adapter[{}]: Wiring BPMN process '{}' for {}", adapterId, bpmnProcessId, workflowModuleId);
    notifyListeners("wireBpmn", workflowModuleId, bpmnProcessId);

    // Like a real adapter: check that every task of the "BPMN" has a @WorkflowTask method,
    // and the other way round. The test's DummyTaskWiringSource supplies the tasks,
    // because the double has no real model. An exception thrown here follows the
    // deployment-failure policy on its own.
    taskWiringSource
        .first()
        .ifPresent(
            source -> collaborators.workflowTaskWiring().validateTaskWiring(
                adapterId,
                workflowModuleId,
                bpmnProcessId,
                source.tasksOf(adapterId, workflowModuleId, bpmnProcessId)));

    // Like a real adapter: report the expressions of the model. Some expressions are more
    // than the name of a variable, and the core says what those bind.
    taskWiringSource
        .first()
        .ifPresent(
            source -> collaborators.workflowTaskWiring().reportModelExpressions(
                workflowModuleId,
                bpmnProcessId,
                source.expressionsOf(adapterId, workflowModuleId, bpmnProcessId)));

    // Like a real adapter: report the start events the BPMS fires on its own. The core
    // checks the application's @WorkflowStartedByBpms methods against them.
    collaborators
        .bpmsInitiatedStartInvoker()
        .ifPresent(
            invoker -> bpmsInitiatedStartSource
                .first()
                .ifPresent(
                    source -> invoker.validateBpmsInitiatedStarts(
                        workflowModuleId,
                        bpmnProcessId,
                        source.startEventsOf(adapterId, workflowModuleId, bpmnProcessId))));

    // Like a real adapter which reads its model: report the messages which start the
    // process. The core checks a start by message against them.
    collaborators
        .bpmsInitiatedStartInvoker()
        .ifPresent(
            invoker -> bpmsInitiatedStartSource
                .first()
                .map(source -> source.startMessagesOf(adapterId, workflowModuleId, bpmnProcessId))
                .ifPresent(
                    messageNames -> invoker.reportStartMessages(
                        adapterId,
                        workflowModuleId,
                        bpmnProcessId,
                        messageNames)));

    // Like a real adapter which can answer about its deployed versions: hand over the
    // catalog. Then a version specification which names a version tag can be resolved.
    processVersionSource
        .first()
        .ifPresent(
            source -> collaborators
                .workflowTaskWiring()
                .registerProcessVersions(adapterId, workflowModuleId, bpmnProcessId, processVersions));
    // Also like a real adapter: report the version the BPMS gave the model of this start.
    processVersionSource
        .first()
        .map(source -> source.deployedVersionOf(adapterId, workflowModuleId, bpmnProcessId))
        .ifPresent(
            version -> collaborators
                .workflowTaskWiring()
                .registerDeployedVersion(adapterId, workflowModuleId, bpmnProcessId, version));

    // Like a real adapter: the start of a called process is a step of the workflow which
    // called it. So it gets no start listener.
    if (collaborators
        .bpmsInitiatedStartInvoker()
        .map(invoker -> invoker.startsAWorkflowOfItsOwn(workflowModuleId, bpmnProcessId))
        .orElse(Boolean.FALSE)) {
      processesWithStartListener.add(bpmnProcessId);
    }

    // Like a real adapter: a model pays for the end notification only where the
    // application asked for one.
    if (collaborators
        .workflowEndedInvoker()
        .map(invoker -> invoker.workflowEndedHandlerExists(workflowModuleId, bpmnProcessId))
        .orElse(Boolean.FALSE)) {
      processesWithEndListener.add(bpmnProcessId);
      log.info(
          "Dummy-Adapter[{}]: attaching an end listener to BPMN process '{}' of {}",
          adapterId,
          bpmnProcessId,
          workflowModuleId);
    }

  }

  @Override
  public void deployResources(
      final String workflowModuleId,
      final Object bpmsProcessingContext) throws IllegalStateException {

    log.info("Dummy-Adapter[{}]: Deploying resources for {}", adapterId, workflowModuleId);
    notifyListeners("deployResources", workflowModuleId, null);

    // Like a real adapter: nothing for the whole module is called here. Since the SPI was
    // split, the reverse wiring check and the version resolution are the core's own job
    // (see WorkflowTaskWiring). So an adapter cannot forget them.

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Object bpmsProcessingContext) {

    log.info("Dummy-Adapter[{}]: Starting workflow processing for {}", adapterId, workflowModuleId);
    notifyListeners("startWorkflowProcessing", workflowModuleId, null);

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId,
      final Object bpmsProcessingContext) {

    log.info("Dummy-Adapter[{}]: Stopping workflow processing for {}", adapterId, workflowModuleId);
    notifyListeners("stopWorkflowProcessing", workflowModuleId, null);

  }

}
