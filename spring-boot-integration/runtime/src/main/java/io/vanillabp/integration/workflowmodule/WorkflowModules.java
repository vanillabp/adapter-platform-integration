package io.vanillabp.integration.workflowmodule;

import java.util.List;

import lombok.Getter;

/**
 * A bean holding all workflow modules found.
 */
@Getter
public class WorkflowModules {

  /**
   * The workflow modules
   */
  private final List<WorkflowModule> workflowModules;

  private boolean workflowServicesAssociated = false;

  /**
   * Built while the application boots, from the marker files found in the classpath, and
   * built directly by tests which want a fixed set of modules. The list is taken as it is
   * given: the order is the order the classpath was walked in, and nothing here sorts it.
   *
   * @param workflowModules The workflow modules of this application, possibly none
   */
  public WorkflowModules(
      final List<WorkflowModule> workflowModules) {

    this.workflowModules = workflowModules;

  }

  /**
   * Associates the given workflow service classes with the workflow modules held by
   * this bean (see
   * {@link WorkflowModuleAutoConfiguration#registerProcessServices(List, List)}).
   * The association is done only once, no matter how often this method is called.
   * It is called lazily on creation of the first
   * {@link io.vanillabp.spi.process.ProcessService} bean, so the workflow modules
   * are determined using the application's resource loader.
   *
   * @param allWorkflowServiceClasses All classes annotated by
   *     {@link io.vanillabp.spi.service.WorkflowService} found in the classpath
   */
  public synchronized void associateWorkflowServices(
      final List<Class<?>> allWorkflowServiceClasses) {

    if (workflowServicesAssociated) {
      return;
    }
    WorkflowModuleAutoConfiguration.registerProcessServices(
        workflowModules,
        allWorkflowServiceClasses);
    workflowServicesAssociated = true;

  }

}
