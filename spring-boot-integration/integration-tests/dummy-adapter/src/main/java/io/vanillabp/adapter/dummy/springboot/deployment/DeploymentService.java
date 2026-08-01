package io.vanillabp.adapter.dummy.springboot.deployment;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.ObjectProvider;

import io.vanillabp.adapter.dummy.springboot.DummyAdapterConfiguration;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskInvoker;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class DeploymentService implements AdapterDeploymentService<Object, Object> {

  private final String adapterId;

  /**
   * The core's task-processing entry point, provided by the platform integration.
   */
  private final WorkflowTaskInvoker workflowTaskInvoker;

  /**
   * Test hook standing in for the BPMN model (see {@link DummyTaskWiringSource}).
   */
  private final ObjectProvider<DummyTaskWiringSource> taskWiringSource;

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

    return DummyAdapterConfiguration.ADAPTER_TYPE;

  }

  @Override
  public List<Map.Entry<String, Object>> readBpmn(
      final String workflowModuleId,
      final String filename,
      final InputStream bpmn,
      final boolean isVanillaBpBpmn) throws BpmnParseException {

    log.info("Dummy-Adapter[{}]: Reading BPMN '{}' for {}", adapterId, filename, workflowModuleId);

    // the BPMN process id is derived from the filename (extension stripped) so
    // tests can distinguish the files of a workflow module - parity with the
    // Quarkus dummy adapter
    final var bpmnProcessId = filename.endsWith(".bpmn")
        ? filename.substring(filename.lastIndexOf('/') + 1, filename.length() - ".bpmn".length())
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

    log.info("Dummy-Adapter: Preparing BPMN for {}", workflowModuleId);


    return new Object();

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final Object model,
      final Object context) {

    log.info("Dummy-Adapter: Wiring BPMN for {}", workflowModuleId);

    // like a real adapter: validate that every task of the "BPMN" (supplied by the
    // test's DummyTaskWiringSource - the dummy has no real model) has a
    // @WorkflowTask method and vice versa; throwing here honors the
    // deployment-failure policy automatically
    taskWiringSource.ifAvailable(
        source -> workflowTaskInvoker.validateTaskWiring(
            workflowModuleId,
            bpmnProcessId,
            source.tasksOf(adapterId, workflowModuleId, bpmnProcessId)));

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
  public void deployResources(
      final String workflowModuleId,
      final Object bpmsProcessingContext) throws IllegalStateException {

    log.info("Dummy-Adapter[{}]: Deploying resources for {}", adapterId, workflowModuleId);

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Object bpmsProcessingContext) {

    log.info("Dummy-Adapter: Starting workflow processing for {}", workflowModuleId);

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId,
      final Object bpmsProcessingContext) {

    log.info("Dummy-Adapter: Stopping workflow processing for {}", workflowModuleId);

  }

}
