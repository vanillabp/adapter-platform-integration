package io.vanillabp.integration.test;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * An annotation of the application which carries <code>&#64;WorkflowService</code>. It is the
 * second shape of the same defect. Jandex resolves no meta-annotation. So it reports THIS
 * annotation type as the annotated one, and nobody sees the class which uses it. See
 * {@code WorkflowServiceViaAnAnnotationOfItsOwnTest}.
 */
@Retention(RUNTIME)
@Target(TYPE)
@io.vanillabp.spi.service.WorkflowService(workflowAggregateClass = Aggregate.class)
public @interface OurOwnWorkflowService {

}
