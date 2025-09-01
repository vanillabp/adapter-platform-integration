package io.vanillabp.integration.deployment.workflowmodule;

import java.util.Map;

import io.quarkus.builder.item.SimpleBuildItem;
import io.quarkus.deployment.ApplicationArchive;
import io.vanillabp.integration.runtime.workflowmodule.WorkflowModule;
import lombok.Builder;
import lombok.Getter;

/**
 * A build item holding all workflow modules found during augmentation.
 */
@Builder
@Getter
public final class VanillaBpWorkflowModulesBuildItem extends SimpleBuildItem {

  /**
   * All workflow modules found during augmentation.
   */
  private Map<WorkflowModule, ApplicationArchive> workflowModules;

}
