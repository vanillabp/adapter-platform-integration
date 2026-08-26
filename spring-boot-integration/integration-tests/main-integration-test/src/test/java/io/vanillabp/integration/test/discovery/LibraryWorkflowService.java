package io.vanillabp.integration.test.discovery;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * A workflow service the way a common library ships one: no stereotype annotation on
 * the class, so no component scan of the application can reach it. It becomes a bean
 * because the library's auto-configuration declares one - see
 * {@link LibraryWorkflowServiceAutoConfiguration}.
 */
@WorkflowService(
    workflowAggregateClass = DiscoveryAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "LibraryProcess"))
public class LibraryWorkflowService {

}
