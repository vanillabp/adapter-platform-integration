package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck;
import io.vanillabp.integration.test.deployment.UncoveredDeployedVersionSource;
import io.vanillabp.integration.test.deployment.VersionedAggregate;
import io.vanillabp.integration.test.deployment.VersionedAggregatePersistence;
import io.vanillabp.integration.test.deployment.VersionedProcessWiringSource;
import io.vanillabp.integration.test.deployment.VersionedWorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The model a start deploys gets a version from the BPMS, and the start ends where no
 * <code>&#64;WorkflowTask</code> method covers that version for a task of the model. The
 * "BPMS" here gave the model version 5, which none of the three methods serves.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TheDeployedVersionNeedsAMethodTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("process-versions/application.yaml", "application.yaml")
          .addClass(VersionedAggregate.class)
          .addClass(VersionedAggregatePersistence.class)
          .addClass(VersionedWorkflowService.class)
          .addClass(VersionedProcessWiringSource.class)
          .addClass(UncoveredDeployedVersionSource.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/VersionedProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          final var message = current.getMessage();
          if ((message != null) && message.startsWith("Version '5' of BPMN process 'VersionedProcess'")) {
            assertTrue(message.contains("(workflow module 'test-module')"), message);
            assertTrue(message.contains("deployed during this start"), message);
            assertTrue(message.contains(DeployedProcessVersionsCheck.SERVED_BY_NO_METHOD), message);
            assertTrue(message.contains("task 'Activity_Versioned' (task definition 'versionedTask')"), message);
            assertTrue(message.contains("(version '1-2')"), message);
            assertTrue(message.contains("(version 'release-2026')"), message);
            assertTrue(message.contains("version = \">=5\""), message);
            return;
          }
          current = current.getCause();
        }
        fail("expected the start to end over the deployed version but got: "
            + throwable);
      });

  @Test
  @DisplayName("A task without a method for the deployed version ends the start")
  public void theStartEnds() {
    // the assertion happens on the startup exception (assertException above)
  }

}
