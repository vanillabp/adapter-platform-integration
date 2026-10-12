package io.vanillabp.integration.test.discovery;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * An annotation of the application which carries {@code @WorkflowService}. It is the second
 * form of the same defect. Spring Boot resolves such a meta-annotation (an annotation on an
 * annotation). Quarkus reports the annotation type itself as the annotated one. See
 * {@link WorkflowServiceDiscoveryTest#anAnnotationOfTheApplicationEndsTheStartNamingBothTypes}.
 */
@Retention(RUNTIME)
@Target(TYPE)
@WorkflowService(
    workflowAggregateClass = MetaAnnotatedAggregate.class,
    bpmnProcess = @BpmnProcess(bpmnProcessId = "MetaAnnotatedProcess"))
public @interface OurOwnWorkflowService {

}
