package io.vanillabp.integration.test.discovery;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import io.vanillabp.spi.service.WorkflowTask;

/**
 * The defect: {@code @WorkflowService} on an interface, with the handler declared in the
 * interface as well. Spring Boot's {@code AnnotationUtils.findAnnotation} searches
 * implemented interfaces, and {@code @Inherited} does not. So an implementing bean used to
 * reach the registrar. The registrar reads the annotation from the class itself and found
 * none. See
 * {@link WorkflowServiceDiscoveryTest#anAnnotatedInterfaceEndsTheStartNamingBothTypes}.
 */
@WorkflowService(
    workflowAggregateClass = InterfaceAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "InterfaceProcess"))
public interface WorkflowServiceInterface {

  @WorkflowTask(taskDefinition = "Activity_Process")
  void processTask(
      InterfaceAggregate aggregate);

}
