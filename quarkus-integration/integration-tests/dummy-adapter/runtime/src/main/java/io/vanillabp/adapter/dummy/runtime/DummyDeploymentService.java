package io.vanillabp.adapter.dummy.runtime;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import jakarta.enterprise.inject.Instance;

/**
 * The dummy adapter's deployment service - one instance per configured adapter id.
 * It does not parse BPMN (model and processing context are plain {@link Object}s);
 * every pipeline call is logged and forwarded to optional
 * {@link DummyDeploymentListener} beans so integration tests can assert the
 * pipeline order and inject failures. The BPMN process id is derived from the
 * filename (extension stripped), so tests can distinguish the files of a workflow
 * module.
 */
public class DummyDeploymentService implements AdapterDeploymentService<Object, Object> {

  private static final Logger log = LoggerFactory.getLogger(DummyDeploymentService.class);

  private final String adapterId;

  private final Instance<DummyDeploymentListener> listeners;

  /**
   * The core's task-processing entry point, provided by the platform integration.
   */
  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * Test hook standing in for the BPMN model (see {@link DummyTaskWiringSource}).
   */
  private final Instance<DummyTaskWiringSource> taskWiringSource;

  public DummyDeploymentService(
      final String adapterId,
      final Instance<DummyDeploymentListener> listeners,
      final WorkflowTaskInvoker workflowTaskInvoker,
      final Instance<DummyTaskWiringSource> taskWiringSource) {

    this.adapterId = adapterId;
    this.listeners = listeners;
    this.workflowTaskInvoker = workflowTaskInvoker;
    this.taskWiringSource = taskWiringSource;

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

    return workflowTaskInvoker.invokeWorkflowTask(workflowModuleId, bpmnProcessId, context);

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

    return DummyProcessServiceProducer.ADAPTER_TYPE;

  }

  private void notifyListeners(
      final String method,
      final String workflowModuleId,
      final String detail) {

    if (listeners == null) {
      return;
    }
    listeners
        .stream()
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

    final var bpmnProcessId = filename.endsWith(".bpmn")
        ? filename.substring(0, filename.length() - ".bpmn".length())
        : filename;
    return List.of(Map.entry(bpmnProcessId, new Object()));

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

    // like a real adapter: validate that every task of the "BPMN" (supplied by the
    // test's DummyTaskWiringSource - the dummy has no real model) has a
    // @WorkflowTask method and vice versa; throwing here honors the
    // deployment-failure policy automatically
    if ((taskWiringSource != null) && taskWiringSource.isResolvable()) {
      workflowTaskInvoker.validateTaskWiring(
          workflowModuleId,
          bpmnProcessId,
          taskWiringSource
              .get()
              .tasksOf(adapterId, workflowModuleId, bpmnProcessId));
    }

  }

  @Override
  public void deployResources(
      final String workflowModuleId,
      final Object bpmsProcessingContext) throws IllegalStateException {

    log.info("Dummy-Adapter[{}]: Deploying resources for {}", adapterId, workflowModuleId);
    notifyListeners("deployResources", workflowModuleId, null);

    // like a real adapter: after ALL processes of the module were wired, methods
    // matching no task of any process are a defect (per-module check)
    if ((taskWiringSource != null) && taskWiringSource.isResolvable()) {
      workflowTaskInvoker.validateNoUnwiredWorkflowTaskMethods(workflowModuleId);
    }

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
