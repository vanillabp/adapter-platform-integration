package io.vanillabp.extension.dummy.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import jakarta.enterprise.inject.Instance;

/**
 * The dummy extension's wiring service - the Quarkus counterpart of the Spring Boot
 * integration's dummy extension. Model and processing context are plain
 * {@link Object}s, so the extension matches every adapter whose declared types are
 * {@code Object} (e.g. the dummy adapter); every pipeline call is logged and
 * forwarded to optional {@link DummyExtensionListener} beans.
 */
public class DummyWiringService implements ExtensionWiringService<Object, Object> {

  private static final Logger log = LoggerFactory.getLogger(DummyWiringService.class);

  private final Instance<DummyExtensionListener> listeners;

  public DummyWiringService(
      final Instance<DummyExtensionListener> listeners) {

    this.listeners = listeners;

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
        .forEach(listener -> listener.onPipelineCall(method, workflowModuleId, detail));

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
  public int getOrder() {

    return 0;

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final Object model,
      final Object context) {

    log.info("Dummy-Extension: Wiring BPMN process '{}' for {}", bpmnProcessId, workflowModuleId);
    notifyListeners("wireBpmn", workflowModuleId, bpmnProcessId);

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Object bpmsProcessingContext) {

    log.info("Dummy-Extension: Starting workflow processing for {}", workflowModuleId);
    notifyListeners("startWorkflowProcessing", workflowModuleId, null);

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId,
      final Object bpmsProcessingContext) {

    log.info("Dummy-Extension: Stopping workflow processing for {}", workflowModuleId);
    notifyListeners("stopWorkflowProcessing", workflowModuleId, null);

  }

}
