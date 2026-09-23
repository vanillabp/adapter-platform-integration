package io.vanillabp.integration.deployment.workflowmodule;

import io.quarkus.builder.item.SimpleBuildItem;
import lombok.Builder;
import lombok.Getter;

/**
 * A build item marking processing of workflow-module-specific config files as done.
 */
@Builder
@Getter
public final class WorkflowModuleSpecificConfigBuilderBuildItem extends SimpleBuildItem {

  /**
   * Built by {@link WorkflowModuleBuildStepProcessor#addWorkflowModuleSpecificConfigFiles}
   * once the generated config builders are registered. {@code ConfigBuildStepProcessor}
   * waits for it, so the config sources of the workflow modules are in place before the
   * migration properties are recorded.
   */
  WorkflowModuleSpecificConfigBuilderBuildItem() {
  }

}
