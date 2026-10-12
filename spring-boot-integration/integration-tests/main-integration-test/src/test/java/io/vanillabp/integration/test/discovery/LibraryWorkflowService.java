package io.vanillabp.integration.test.discovery;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * A workflow service as a common library ships one. The class has no stereotype annotation
 * like {@code @Service}, so no component scan of the application can reach it. It becomes a
 * bean because the library's auto-configuration declares one, see
 * {@link LibraryWorkflowServiceAutoConfiguration}.
 */
@WorkflowService(
    workflowAggregateClass = DiscoveryAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "LibraryProcess"))
public class LibraryWorkflowService {

}
