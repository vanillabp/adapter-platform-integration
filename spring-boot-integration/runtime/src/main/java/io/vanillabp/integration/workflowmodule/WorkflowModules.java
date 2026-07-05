package io.vanillabp.integration.workflowmodule;

import java.util.List;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A bean holding all workflow modules found.
 */
@RequiredArgsConstructor
@Getter
public class WorkflowModules {

  /**
   * The workflow modules
   */
  private final List<WorkflowModule> workflowModules;

  private boolean workflowServicesAssociated = false;

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
