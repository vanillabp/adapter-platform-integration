package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.test.deployment.RequestScopedProbe;
import io.vanillabp.integration.test.deployment.TaskAggregate;
import io.vanillabp.integration.test.deployment.TaskAggregatePersistence;
import io.vanillabp.integration.test.deployment.TaskWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Guiding wiring validation on Quarkus (story 21a): a "BPMN task" (supplied by a
 * wiring source standing in for the model) without a matching
 * <code>&#64;WorkflowTask</code> method aborts the boot with a message naming the
 * task and the fix (the reverse direction - methods matching no task - is
 * validated per module at the end of deployResources).
 */
@ExtendWith(SuppressOutputExtension.class)
public class IncompleteTaskWiringTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("task-processing/application.yaml", "application.yaml")
          .addClass(TaskAggregate.class)
          .addClass(TaskAggregatePersistence.class)
          .addClass(TaskWorkflowService.class)
          .addClass(RequestScopedProbe.class)
          .addClass(BrokenWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TaskProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          if ((current.getMessage() != null) && current.getMessage()
              .contains("Task wiring of BPMN process 'TaskProcess'")) {
            assertTrue(current.getMessage().contains("'Activity_Unknown'"));
            assertTrue(current.getMessage().contains("@WorkflowTask(taskDefinition = \"notImplemented\")"));
            return;
          }
          current = current.getCause();
        }
        fail("expected the guiding wiring message but got: "
            + throwable);
      });

  @Test
  @DisplayName("Incomplete task wiring aborts the boot with guiding messages")
  public void incompleteWiringAbortsBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
