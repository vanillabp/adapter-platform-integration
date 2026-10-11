package io.vanillabp.integration.test.extension;

import java.util.Collection;
import java.util.List;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

import io.vanillabp.bpmsdouble.DummyTaskAwarenessSource;
import io.vanillabp.bpmsdouble.DummyTaskWiringSource;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.adapter.spi.workflowtask.BpmnTaskSpec;

/**
 * The application of this scenario. It has two workflow aggregates, and one of them uses
 * the sample extension. The BPMS double is the engine behind both.
 */
@SpringBootApplication
public class TestApplication {

  /**
   * The id which no workflow of this scenario ever has. The tests ask the election about
   * it to see it refuse.
   */
  public static final long UNKNOWN_AGGREGATE_ID = 4711L;

  /**
   * Lets the BPMS double report the workflows of this scenario as running. So the election
   * has something to elect. The one id above is unknown on purpose.
   *
   * @return The awareness the double reports
   */
  @Bean
  public DummyTaskAwarenessSource everyWorkflowRunsHere() {

    return (
        adapterId,
        workflowAggregateId,
        taskId) -> String.valueOf(UNKNOWN_AGGREGATE_ID).equals(String.valueOf(workflowAggregateId))
            ? WorkflowAwareness.UNKNOWN_TO_BPMS
            : WorkflowAwareness.ACTIVE;

  }

  /**
   * The element the wiring below reports a name for.
   */
  public static final String USER_TASK_ID = "Activity_Review";

  /**
   * What a modeller wrote on that element. An extension asks VanillaBP for this label
   * instead of parsing the same BPMN bytes a second time.
   */
  public static final String USER_TASK_NAME = "Review the note";

  /**
   * The task definition of the same element. It is the second name of the element, and the
   * extension offers it after the id.
   */
  public static final String USER_TASK_DEFINITION = "reviewTheNote";

  /**
   * Stands in for what an adapter reads out of the models of this scenario: the BPMN
   * process each file declares, and its tasks. Without the first part, the double names a
   * process after its file. Then nobody would wire the processes the workflow services
   * claim.
   * <p>
   * The one task is a USER task. It needs no handler. So it fits a scenario whose workflow
   * services have methods of the extension and no <code>&#64;WorkflowTask</code>. It
   * carries the name a modeller wrote on it.
   * {@code ExtensionHandlerTest#theExtensionReadsTheBpmnName} asks VanillaBP for this name.
   *
   * @return What this scenario's BPMN files hold
   */
  @Bean
  public DummyTaskWiringSource notedProcessModels() {

    return new DummyTaskWiringSource() {

      @Override
      public List<String> executableProcessesOf(
          final String adapterId,
          final String workflowModuleId,
          final String filename) {

        return switch (filename) {
          case "dummy-process.bpmn" -> List.of("DummyProcess");
          case "unnoted-process.bpmn" -> List.of("UnnotedProcess");
          default -> List.of();
        };

      }

      @Override
      public Collection<BpmnTaskSpec> tasksOf(
          final String adapterId,
          final String workflowModuleId,
          final String bpmnProcessId) {

        return "DummyProcess".equals(bpmnProcessId)
            ? List.of(BpmnTaskSpec.userTask(USER_TASK_ID, USER_TASK_DEFINITION, USER_TASK_NAME))
            : List.of();

      }

    };

  }

}
