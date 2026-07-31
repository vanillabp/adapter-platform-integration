package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.test.deployment.Aggregate;
import io.vanillabp.integration.test.deployment.AggregatePersistence;
import io.vanillabp.integration.test.deployment.FailDeploymentListener;
import io.vanillabp.integration.test.deployment.RecordingDeploymentEvents;
import io.vanillabp.integration.test.deployment.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Deployment-failure policy on Quarkus, <code>fail</code> case (the default,
 * identical to Spring Boot): a failing deployment aborts the boot - here for a
 * non-first-priority adapter WITHOUT <code>deployment-failure: warn</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DeploymentFailureFailTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("failure-fail/application.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingDeploymentEvents.class)
          .addClass(FailDeploymentListener.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/first.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> assertTrue(
          hasCauseWithMessagePart(throwable, "deployment failed for testing purposes (adapter 'demo2')"),
          "expected the deployment failure to abort the boot but got: "
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
  @DisplayName("A failing deployment with the default policy 'fail' aborts the boot")
  public void failingDeploymentAbortsBoot() {
    // the assertion happens on the startup exception (assertException above)
  }

}
