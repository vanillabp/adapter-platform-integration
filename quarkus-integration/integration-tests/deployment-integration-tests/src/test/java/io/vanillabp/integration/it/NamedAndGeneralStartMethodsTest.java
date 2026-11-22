package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.deployment.NamedAndGeneralStartWorkflowService;
import io.vanillabp.integration.test.deployment.StartAggregate;
import io.vanillabp.integration.test.deployment.StartAggregatePersistence;
import io.vanillabp.integration.test.deployment.StartProcessStartEventSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * A <code>&#64;WorkflowStartedByBpms</code> method which names a start event, next to one
 * which serves every start event of the same process, ends the boot. Both match the named
 * start event. Without the check, a start would run the method which the JVM happens to
 * list first.
 */
@ExtendWith(SuppressOutputExtension.class)
public class NamedAndGeneralStartMethodsTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("bpms-initiated-start/application.yaml", "application.yaml")
          .addClass(StartAggregate.class)
          .addClass(StartAggregatePersistence.class)
          .addClass(NamedAndGeneralStartWorkflowService.class)
          .addClass(StartProcessStartEventSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/StartProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {

        final var service = NamedAndGeneralStartWorkflowService.class.getName();
        assertTrue(
            hasCauseWithMessagePart(throwable, service
                + "#aggregateOfTimerStart"),
            "expected the method naming the start event but got: "
                + throwable);
        assertTrue(
            hasCauseWithMessagePart(throwable, service
                + "#aggregateOfEveryStart"),
            "expected the method serving every start event but got: "
                + throwable);
        assertTrue(
            hasCauseWithMessagePart(throwable, "@WorkflowStartedByBpms(id = ...)"),
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
  @DisplayName("A named and a general start method for one process end the boot, naming both")
  public void aNamedAndAGeneralMethodEndTheBoot() {
    // the assertException callback holds the assertions
  }

}
