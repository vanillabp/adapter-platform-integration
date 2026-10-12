package io.vanillabp.integration.test.processservice;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.deployment.processservice.ProcessServiceBuildStepProcessor;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.BpmnProcess;
import io.vanillabp.spi.service.WorkflowService;

/**
 * This test makes sure that the {@link WorkflowService} annotations which
 * {@link ProcessServiceBuildStepProcessor} processes have the expected attributes. The
 * Quarkus framework reads attribute values by name. So the compiler does not notice it
 * when the annotation changes.
 */
@WorkflowService(workflowAggregateClass = WorkflowServiceAnnotationTest.class)
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowServiceAnnotationTest {

  static final String SECONDARY_BPMN_PROCESS_ID = "secondary";

  @Test
  public void testForAttributeWorkflowAggregateClass() throws NoSuchMethodException {

    final var testAnnotation = getClass().getAnnotation(WorkflowService.class);
    Assertions.assertNotNull(testAnnotation.getClass()
        .getDeclaredMethod(ProcessServiceBuildStepProcessor.ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_AGGREGATECLASS));
    Assertions.assertNotNull(testAnnotation);
    Assertions.assertEquals(WorkflowServiceAnnotationTest.class, testAnnotation.workflowAggregateClass());

  }

  @Test
  public void testForAttributeBpmnProcess() throws NoSuchMethodException {

    final var testAnnotation = getClass().getAnnotation(WorkflowService.class);
    Assertions.assertNotNull(testAnnotation);
    Assertions.assertNotNull(WorkflowService.class
        .getDeclaredMethod(ProcessServiceBuildStepProcessor.ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS));
    Assertions.assertNotNull(BpmnProcess.class
        .getDeclaredMethod(
            ProcessServiceBuildStepProcessor.ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_BPMNPROCESS_BPMNPROCESSID));
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
  public void testForAttributeSecondaryBpmnProcesses() throws NoSuchMethodException {

    final var testAnnotation = getClass().getAnnotation(WorkflowService.class);
    Assertions.assertNotNull(testAnnotation);
    Assertions.assertNotNull(WorkflowService.class
        .getDeclaredMethod(
            ProcessServiceBuildStepProcessor.ANNOTATION_WORKFLOWSERVICE_ATTRIBUTE_SECONDARYBPMNPROCESSES));
    final var secondaryBpmnProcesses = testAnnotation.secondaryBpmnProcesses();
    Assertions.assertNotNull(secondaryBpmnProcesses);
    Assertions.assertArrayEquals(new BpmnProcess[0], secondaryBpmnProcesses);

  }

}
