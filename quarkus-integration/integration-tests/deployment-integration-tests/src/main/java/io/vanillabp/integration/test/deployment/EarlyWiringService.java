package io.vanillabp.integration.test.deployment;

import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * A second extension, which the application provides. Its order is BEFORE the dummy
 * extension (order 0). A test uses it to check that extensions are wired and started in
 * the order of {@code getOrder()}. Events are recorded as
 * <code>extensionEarly:&lt;method&gt;:&lt;moduleId&gt;</code>.
 */
@ApplicationScoped
public class EarlyWiringService implements ExtensionWiringService<Object, Object> {

  @Inject
  RecordingDeploymentEvents events;

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

    return -10;

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final Object model,
      final Object context) {

    events.record("extensionEarly:wireBpmn:%s:%s".formatted(workflowModuleId, bpmnProcessId));

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final Object bpmsProcessingContext) {

    events.record("extensionEarly:startWorkflowProcessing:%s".formatted(workflowModuleId));

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId,
      final Object bpmsProcessingContext) {

    events.record("extensionEarly:stopWorkflowProcessing:%s".formatted(workflowModuleId));

  }

}
