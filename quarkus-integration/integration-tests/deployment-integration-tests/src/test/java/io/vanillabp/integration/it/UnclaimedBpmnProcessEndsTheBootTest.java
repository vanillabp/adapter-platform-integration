package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.deployment.CallingAndCalledWiringSource;
import io.vanillabp.integration.test.deployment.CallingWorkflowService;
import io.vanillabp.integration.test.deployment.TaskAggregate;
import io.vanillabp.integration.test.deployment.TaskAggregatePersistence;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The file of {@link UnclaimedBpmnProcessTest} without the line saying that something else
 * serves the second process. The start cannot tell a forgotten workflow service from a process
 * somebody else serves, so it ends and names both ways out.
 */
@ExtendWith(SuppressOutputExtension.class)
public class UnclaimedBpmnProcessEndsTheBootTest {

  /**
   * The first words of the refusal, which {@link UnclaimedBpmnProcessTest} looks for in vain.
   */
  static final String REFUSAL = "deploys BPMN processes which no @WorkflowService class of this application claims";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("unclaimed-process-unmarked/application.yaml", "application.yaml")
          .addClass(TaskAggregate.class)
          .addClass(TaskAggregatePersistence.class)
          .addClass(CallingWorkflowService.class)
          .addClass(CallingAndCalledWiringSource.class)
          .addAsResource("bpmn/calling-and-called.bpmn", "processes/unclaimed/CallingAndCalled.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          if ((current.getMessage() != null) && current.getMessage().contains(REFUSAL)) {
            final var message = current.getMessage();
            assertTrue(message.contains("Workflow module 'test-module'"), message);
            assertTrue(message.contains("process 'Called' of file"), message);
            assertTrue(message.contains("CallingAndCalled.bpmn"), message);
            assertTrue(message.contains("@WorkflowService(bpmnProcess"), message);
            assertTrue(
                message.contains("vanillabp.workflow-modules.test-module.workflows.Called.implemented-externally=true"),
                message);
            assertFalse(message.contains("- process 'Calling' of file"), message);
            return;
          }
          current = current.getCause();
        }
        fail("expected the refusal of the process nobody claims but got: "
            + throwable);
      });

  @Test
  @DisplayName("A process nobody claims and nobody marked ends the boot")
  public void anUnclaimedProcessEndsTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
