package io.vanillabp.integration.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * One workflow aggregate has one {@code ProcessService}, so one of the
 * classes declaring the aggregate names the process {@code startWorkflow} starts.
 * That used to be whichever class the classpath scan returned first - an order coming
 * from the file system.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PrimaryWorkflowServiceClassTest {

  public static class Aggregate {
  }

  /**
   * The fixtures carry NO {@code @WorkflowService}: annotated classes on this test
   * classpath would be found by the classpath scan of every other test in this
   * module. Their process is handed to the rule instead.
   */
  public static class CallingWorkflowService {
  }

  public static class CalledWorkflowService {
  }

  public static class SecondHalfOfTheProcess {
  }

  private static final Map<Class<?>, String> PROCESSES = Map.of(
      CallingWorkflowService.class, "LoanApproval",
      CalledWorkflowService.class, "RiskAssessment",
      SecondHalfOfTheProcess.class, "LoanApproval");

  @Test
  @DisplayName("Two classes naming different processes for one aggregate end the boot")
  public void differentPrimaryProcessesAreReported() {

    final var failure = assertThrows(
        IllegalStateException.class,
        () -> ProcessServiceBeanRegistrar.primaryWorkflowServiceClass(
            List.of(CallingWorkflowService.class, CalledWorkflowService.class),
            Aggregate.class,
            PROCESSES::get));

    final var message = failure.getMessage();
    assertTrue(message.contains(CallingWorkflowService.class.getName()), message);
    assertTrue(message.contains(CalledWorkflowService.class.getName()), message);
    assertTrue(message.contains("LoanApproval"), message);
    assertTrue(message.contains("RiskAssessment"), message);
    // the way out is part of it: one class declares the process to be started, the
    // others move under its secondaryBpmnProcesses
    assertTrue(message.contains("secondaryBpmnProcesses"), message);

  }

  @Test
  @DisplayName("Classes sharing one process are not ambiguous, and the choice is reproducible")
  public void sharedPrimaryProcessIsAccepted() {

    assertEquals(
        CallingWorkflowService.class,
        ProcessServiceBeanRegistrar.primaryWorkflowServiceClass(
            List.of(SecondHalfOfTheProcess.class, CallingWorkflowService.class),
            Aggregate.class,
            PROCESSES::get),
        "with one process the class is chosen by name, not by the order of the scan");
    assertEquals(
        CallingWorkflowService.class,
        ProcessServiceBeanRegistrar.primaryWorkflowServiceClass(
            List.of(CallingWorkflowService.class, SecondHalfOfTheProcess.class),
            Aggregate.class,
            PROCESSES::get));

  }

}
