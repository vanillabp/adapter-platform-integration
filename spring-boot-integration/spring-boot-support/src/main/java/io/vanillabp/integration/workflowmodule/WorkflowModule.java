package io.vanillabp.integration.workflowmodule;

import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
@Getter
@Builder
public class WorkflowModule {

  /**
   * The location of workflow module definition files.
   */
  public static final String METAINF_WORKFLOWMODULE = "META-INF/workflow-module";

  private final String workflowModuleId;

  private final URI sourceUri;

  private final Set<Class<?>> workflowServices = new HashSet<>();

  public boolean isWorkflowServiceKnown(
      final Class<?> workflowService) {

    return workflowServices.contains(workflowService);

  }

  void addWorkflowService(
      final Class<?> workflowService) {

    workflowServices.add(workflowService);

  }

  void addWorkflowServices(
      final Collection<Class<?>> workflowServices) {

    this.workflowServices.addAll(workflowServices);

  }

}
