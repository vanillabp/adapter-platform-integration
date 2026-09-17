package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.deployment.Aggregate;
import io.vanillabp.integration.test.deployment.AggregatePersistence;
import io.vanillabp.integration.test.deployment.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The four places a workflow module may use do not rank against each other, so the same
 * file may lie in one of them only. A module which ships it twice ends the boot instead of
 * letting one of the two win (see decision 65 in the repository's DECISIONS.md).
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheSameFileInTwoPlacesTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("config-directory/application.yaml", "application.yaml")
          .addAsResource("config-directory/test-module.yaml", "config/test-module.yaml")
          .addAsResource("config-directory/test-module.yaml", "test-module/test-module.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/first.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> assertTrue(
          hasCauseWithMessagePart(throwable, "'config/test-module.yaml'") && hasCauseWithMessagePart(throwable,
              "'test-module/test-module.yaml'"),
          "expected the boot to end naming both places but got: "
              + throwable));

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
  @DisplayName("A file shipped in two places ends the boot naming both")
  public void aFileShippedTwiceEndsTheBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
