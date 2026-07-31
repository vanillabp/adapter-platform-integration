package io.vanillabp.adapter.dummy.runtime;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.BpmnParseException;
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

  public DummyDeploymentService(
      final String adapterId,
      final Instance<DummyDeploymentListener> listeners) {

    this.adapterId = adapterId;
    this.listeners = listeners;

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

  }

  @Override
  public void deployResources(
      final String workflowModuleId,
      final Object bpmsProcessingContext) throws IllegalStateException {

    log.info("Dummy-Adapter[{}]: Deploying resources for {}", adapterId, workflowModuleId);
    notifyListeners("deployResources", workflowModuleId, null);

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
