package io.vanillabp.integration.test.deployment;

import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * An extension whose declared model/context types ({@link String}) do NOT match the
 * dummy adapter's types ({@link Object}) - the deployment pipeline must never wire
 * or start it. Any recorded <code>extensionNonMatching:*</code> event is a test
 * failure.
 */
@ApplicationScoped
public class NonMatchingWiringService implements ExtensionWiringService<String, String> {

  @Inject
  RecordingDeploymentEvents events;

  @Override
  public Class<String> getModelType() {

    return String.class;

  }

  @Override
  public Class<String> getProcessContextType() {

    return String.class;

  }

  @Override
  public void wireBpmn(
      final String workflowModuleId,
      final String filename,
      final String bpmnProcessId,
      final String model,
      final String context) {

    events.record("extensionNonMatching:wireBpmn:%s".formatted(workflowModuleId));

  }

  @Override
  public void startWorkflowProcessing(
      final String workflowModuleId,
      final String bpmsProcessingContext) {

    events.record("extensionNonMatching:startWorkflowProcessing:%s".formatted(workflowModuleId));

  }

  @Override
  public void stopWorkflowProcessing(
      final String workflowModuleId,
      final String bpmsProcessingContext) {

    events.record("extensionNonMatching:stopWorkflowProcessing:%s".formatted(workflowModuleId));

  }

}
