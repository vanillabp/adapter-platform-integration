package io.vanillabp.integration.deployment.workflowmodule;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * A build item marking the report about the configuration files of workflow modules which
 * this application does not read as prepared. The report is written when the application
 * starts, because that is the log a developer reads.
 */
public final class WorkflowModuleConfigFilesReportedBuildItem extends SimpleBuildItem {

  /**
   * Built by {@link WorkflowModuleBuildStepProcessor#reportConfigFilesWhichStayUnread}. The
   * step registering the config builders waits for it, which is what keeps the report in
   * the build: a step whose item nobody asks for is dropped instead of being run.
   */
  public WorkflowModuleConfigFilesReportedBuildItem() {
  }

}
