package io.vanillabp.integration.deployment.processservice;

import java.util.List;

import org.jboss.jandex.DotName;

import io.quarkus.builder.item.SimpleBuildItem;

/**
 * The workflow-aggregate classes of this application - the same universe
 * {@code ProcessService} beans are built for, published so that a second build step can
 * build one bean per aggregate as well without scanning the archives again.
 * <p>
 * That second build step is the one giving an extension its own per-aggregate service
 * (see {@code ExtensionServiceBuildStepProcessor}).
 */
public final class VanillaBpWorkflowAggregatesBuildItem extends SimpleBuildItem {

  private final List<DotName> workflowAggregateClasses;

  /**
   * Built by {@code ProcessServiceBuildStepProcessor} once it knows the aggregates it built
   * a {@code ProcessService} for, and read by {@code ExtensionServiceBuildStepProcessor}.
   *
   * @param workflowAggregateClasses The aggregate classes, each of them once. The item keeps
   *          a copy, so the producing step may go on working with its own list
   */
  public VanillaBpWorkflowAggregatesBuildItem(
      final List<DotName> workflowAggregateClasses) {

    this.workflowAggregateClasses = List.copyOf(workflowAggregateClasses);

  }

  /**
   * The aggregates an extension's build step builds its own beans for.
   *
   * @return The workflow-aggregate classes, in the order the archives were scanned. The list
   *         is immutable, so a reader may pass it on as it is
   */
  public List<DotName> getWorkflowAggregateClasses() {

    return workflowAggregateClasses;

  }

}
