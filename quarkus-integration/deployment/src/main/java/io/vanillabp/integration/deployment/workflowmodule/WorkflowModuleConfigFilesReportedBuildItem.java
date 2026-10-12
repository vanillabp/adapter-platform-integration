package io.vanillabp.integration.deployment.workflowmodule;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * A build item which marks a report as prepared. The report is about the configuration
 * files of workflow modules which this application does not read. It is written when the
 * application starts, because a developer reads that log.
 */
public final class WorkflowModuleConfigFilesReportedBuildItem extends SimpleBuildItem {

  /**
   * Built by {@link WorkflowModuleBuildStepProcessor#reportConfigFilesWhichStayUnread}. The
   * step which registers the config builders waits for it, and this keeps the report in the
   * build. Quarkus drops a step whose item nobody asks for, instead of running it.
   */
  public WorkflowModuleConfigFilesReportedBuildItem() {
  }

}
