package io.vanillabp.integration.test.samples.annotatedbeanbase;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;
import jakarta.inject.Singleton;

/**
 * A declaration on a class which is a bean itself. So it does not only declare the process.
 * It also serves it, next to its subclass.
 */
@Singleton
@WorkflowService(
    workflowAggregateClass = BeanBaseAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "BaseProcess"))
public class AnnotatedBaseWhichIsABean {

}
