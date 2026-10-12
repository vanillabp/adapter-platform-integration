package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.workflowtask.DeployedProcessVersionsCheck;
import io.vanillabp.integration.test.deployedversion.DeployedVersionNote;
import io.vanillabp.integration.test.deployedversion.DeployedVersionNoteWiringService;
import io.vanillabp.integration.test.deployedversion.NotedVersionedWorkflowService;
import io.vanillabp.integration.test.deployment.UncoveredDeployedVersionSource;
import io.vanillabp.integration.test.deployment.VersionedAggregate;
import io.vanillabp.integration.test.deployment.VersionedAggregatePersistence;
import io.vanillabp.integration.test.deployment.VersionedProcessWiringSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The contract of this extension asks for a method for the deployed version. The extension
 * reports the elements of the model in its <code>wireBpmn</code>. The start ends if an element
 * has methods, but none for the version which the BPMS gave the model. The "BPMS" here gave
 * the model version 5, and the note of the test extension serves versions 1 to 4.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ExtensionElementsOfTheDeployedVersionTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("process-versions/application.yaml", "application.yaml")
          .addClass(VersionedAggregate.class)
          .addClass(VersionedAggregatePersistence.class)
          .addClass(NotedVersionedWorkflowService.class)
          .addClass(DeployedVersionNote.class)
          .addClass(DeployedVersionNoteWiringService.class)
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
            assertTrue(message.contains(DeployedProcessVersionsCheck.ELEMENTS_SERVED_BY_NO_METHOD_IN_THIS_VERSION),
                message);
            assertTrue(
                message.contains("element 'Activity_Versioned' (also matched by 'versionedTask'): @DeployedVersionNote "
                    + "method '%s#noteUpToFour' of extension 'deployed-version-note' (version '1-4'), which does not "
                        .formatted(NotedVersionedWorkflowService.class.getName())
                    + "cover version '5'."),
                message);
            assertTrue(
                message.contains("For @DeployedVersionNote of extension 'deployed-version-note': "
                    + DeployedVersionNoteWiringService.CONSEQUENCE),
                message);
            assertFalse(message.contains("task 'Activity_Versioned'"), "the task is served: "
                + message);
            assertFalse(message.contains("the process itself"), "no method notes the process: "
                + message);
            return;
          }
          current = current.getCause();
        }
        fail("expected the start to end over the note of the deployed version but got: "
            + throwable);
      });

  @Test
  @DisplayName("An element of an extension without a method for the deployed version ends the start")
  public void theStartEnds() {
    // the assertion happens on the startup exception (assertException above)
  }

}
