package io.vanillabp.integration.test.workflowversion;

import java.util.List;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.vanillabp.integration.extension.spi.ExtensionWiringService;
import io.vanillabp.integration.extension.spi.handler.CoreHandlerParameter;
import io.vanillabp.integration.extension.spi.handler.ExtensionHandlers;
import io.vanillabp.integration.extension.spi.handler.HandlerContract;

/**
 * A test extension whose contract asks for a method for the version which a start
 * deployed. In its <code>wireBpmn</code> it registers the contract. It also reports two
 * elements of the model: the task and the process itself.
 */
@Configuration
public class DeployedVersionNoteExtension {

  /**
   * The sentence the extension gives for an element without a method.
   */
  static final String CONSEQUENCE = "The note of such an element stays empty.";

  @Bean
  ExtensionWiringService<Object, Object> deployedVersionNoteWiringService(
      final ExtensionHandlers extensionHandlers) {

    return new ExtensionWiringService<>() {

      private boolean registered;

      @Override
      public Class<Object> getModelType() {

        return Object.class;

      }

      @Override
      public Class<Object> getProcessContextType() {

        return Object.class;

      }

      @Override
      public synchronized void wireBpmn(
          final String workflowModuleId,
          final String filename,
          final String bpmnProcessId,
          final Object model,
          final Object context) {

        if (!registered) {
          extensionHandlers.register(contract());
          registered = true;
        }
        extensionHandlers
            .elementsOfTheDeployedModel(
                DeployedVersionNote.class,
                workflowModuleId,
                bpmnProcessId,
                List.of(List.of("Activity_Versioned", "versionedTask"), List.of()));

      }

      @Override
      public void startWorkflowProcessing(
          final String workflowModuleId,
          final Object bpmsProcessingContext) {
      }

      @Override
      public void stopWorkflowProcessing(
          final String workflowModuleId,
          final Object bpmsProcessingContext) {
      }

    };

  }

  private static HandlerContract contract() {

    return HandlerContract
        .of("deployed-version-note", DeployedVersionNote.class)
        .lookupKeys(annotation -> List.of(((DeployedVersionNote) annotation).element()))
        .versions(annotation -> List.of(((DeployedVersionNote) annotation).version()))
        .callsCarryTheProcessVersion()
        .theDeployedVersionNeedsAMethod(CONSEQUENCE)
        .coreParameters(CoreHandlerParameter.WORKFLOW_AGGREGATE)
        .build();

  }

}
