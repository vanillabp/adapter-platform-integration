package io.vanillabp.integration.test.discovery;

import org.springframework.stereotype.Service;

/**
 * A workflow service by inheritance: {@code @WorkflowService} is {@code @Inherited},
 * so a subclass of an annotated class serves the process of its superclass.
 */
@Service
public class InheritedWorkflowService extends AnnotatedWorkflowServiceBase {

}
