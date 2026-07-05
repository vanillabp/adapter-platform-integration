package io.vanillabp.integration.workflowmodule;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Meta-data of a workflow module.
 */
@RequiredArgsConstructor
@Getter
@Builder
public class WorkflowModule {

  /**
   * The location of workflow module definition files.
   */
  public static final String METAINF_WORKFLOWMODULE = "META-INF/workflow-module";

  /**
   * The workflow module ID.
   */
  private final String id;

  /**
   * The classpath-root prefix (external URL form) of the JAR or directory the
   * workflow module descriptor was loaded from (the descriptor URL minus
   * {@link #METAINF_WORKFLOWMODULE}). Used to match classes originating from the
   * same JAR or directory. Comparing URL-prefix strings works for all class
   * loaders: plain classpath ({@code file:}), JARs ({@code jar:file:}) and Spring
   * Boot repackaged fat JARs ({@code jar:nested:}).
   */
  private final String sourceUri;

  /**
   * Workflow services associated to this workflow module.
   */
  private final Set<Class<?>> workflowServices = new HashSet<>();

  /**
   * @param workflowService A workflow service class
   * @return Whether the workflow service class belongs to this workflow module
   */
  public boolean isWorkflowServiceKnown(
      final Class<?> workflowService) {

    return workflowServices.contains(workflowService);

  }

  /**
   * Add the given workflow service class to the workflow module.
   *
   * @param workflowService The workflow service class
   */
  void addWorkflowService(
      final Class<?> workflowService) {

    workflowServices.add(workflowService);

  }

  /**
   * Add the given workflow service classes to the workflow module.
   *
   * @param workflowServices The workflow service classes
   */
  void addWorkflowServices(
      final Collection<Class<?>> workflowServices) {

    this.workflowServices.addAll(workflowServices);

  }

}
