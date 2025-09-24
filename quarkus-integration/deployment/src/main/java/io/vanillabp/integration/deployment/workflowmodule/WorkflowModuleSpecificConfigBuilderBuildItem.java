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

}
