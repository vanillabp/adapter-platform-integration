package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.deployment.Aggregate;
import io.vanillabp.integration.test.deployment.AggregatePersistence;
import io.vanillabp.integration.test.deployment.OrphanMethodWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A <code>&#64;WorkflowTask</code> method matching no task of any BPMN process of its
 * workflow module is a defect the developer has to learn about while the application
 * starts - a typo in a task definition, or a method left behind after a model change,
 * otherwise stays silent until a workflow reaches the task.
 * <p>
 * The check is old; who runs it is new. It used to be the adapter's duty, written down in
 * the javadoc of the SPI and nowhere else, and Camunda 7 forgot it for a year. Since the
 * SPI was split into
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring} and
 * {@code WorkflowTaskInvoker}, the core runs it once the last adapter of a module finished
 * deploying - the dummy adapter does not call it any more, and the boot still fails.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OrphanWorkflowTaskMethodTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("orphan-method/application.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(OrphanMethodWorkflowService.class)
          .addClass(OrphanMethodWorkflowService.OrphanProcessWiringSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/orphan.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {

        assertTrue(
            hasCauseWithMessagePart(throwable, "activityNobodyModelled"),
            "expected the orphan method to be named but got: "
                + throwable);
        assertTrue(
            hasCauseWithMessagePart(throwable, "fix the annotation"),
            "expected the guiding fix but got: "
                + throwable);

      });

  private static boolean hasCauseWithMessagePart(
      final Throwable throwable,
      final String messagePart) {

    var current = throwable;
    while (current != null) {
      if ((current.getMessage() != null) && current.getMessage().contains(messagePart)) {
        return true;
      }
      current = current.getCause();
    }
    return false;

  }

  @Test
  @DisplayName("A method matching no task ends the boot, naming the method and the fix")
  public void anOrphanMethodEndsTheBoot() {
    // the assertException callback holds the assertions
  }

}
