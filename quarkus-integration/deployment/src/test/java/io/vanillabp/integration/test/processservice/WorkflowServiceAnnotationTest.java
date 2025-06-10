package io.vanillabp.integration.test.processservice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import io.vanillabp.integration.deployment.VanillabpIntegrationProcessor;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * This test is mainly to ensure {@link WorkflowService} annotations processed by
 * {@link io.vanillabp.integration.deployment.VanillabpIntegrationProcessor} have the
 * attributes expected since accessing attribute values using Quarkus framework
 * is done by name and therefor does not show up if the annotation changed.
 */
@WorkflowService(workflowAggregateClass = WorkflowServiceAnnotationTest.class)
public class WorkflowServiceAnnotationTest {

  static final String SECONDARY_BPMN_PROCESS_ID = "secondary";

  @Test
  public void testForAttributeWorkflowAggregateClass() throws NoSuchMethodException {

    final var testAnnotation = getClass().getAnnotation(WorkflowService.class);
    Assertions.assertNotNull(testAnnotation.getClass()
        .getDeclaredMethod(VanillabpIntegrationProcessor.ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_AGGREGATECLASS));
    Assertions.assertNotNull(testAnnotation);
    Assertions.assertEquals(WorkflowServiceAnnotationTest.class, testAnnotation.workflowAggregateClass());

  }

  @Test
  public void testForAttributeBpmnProcess() {

    final var testAnnotation = getClass().getAnnotation(WorkflowService.class);
    Assertions.assertNotNull(testAnnotation);
    final var bpmnProcess = testAnnotation.bpmnProcess();
    Assertions.assertNotNull(bpmnProcess);
    Assertions.assertEquals(BpmnProcess.USE_CLASS_NAME, bpmnProcess.bpmnProcessId());
    Assertions.assertArrayEquals(
        new String[]{
            BpmnProcess.ALL_VERSIONS
        }, bpmnProcess.version()
    );

  }

  @Test
  public void testForAttributeSecondaryBpmnProcesses() {

    final var testAnnotation = getClass().getAnnotation(WorkflowService.class);
    Assertions.assertNotNull(testAnnotation);
    final var secondaryBpmnProcesses = testAnnotation.secondaryBpmnProcesses();
    Assertions.assertNotNull(secondaryBpmnProcesses);
    Assertions.assertArrayEquals(new BpmnProcess[0], secondaryBpmnProcesses);

  }

}
