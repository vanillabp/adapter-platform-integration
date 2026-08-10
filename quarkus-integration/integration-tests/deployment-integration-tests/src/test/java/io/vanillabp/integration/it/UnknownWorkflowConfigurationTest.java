package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.deployment.Aggregate;
import io.vanillabp.integration.test.deployment.AggregatePersistence;
import io.vanillabp.integration.test.deployment.RecordingDeploymentEvents;
import io.vanillabp.integration.test.deployment.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Guiding validation of workflow-level properties (story 27): a configured workflow
 * ID (<code>vanillabp.workflow-modules.&lt;module&gt;.workflows.&lt;id&gt;</code>)
 * matching no executable BPMN process of that module does NOT prevent the boot (the
 * BPMN may arrive later, e.g. during a BPMS migration) but yields a startup WARN
 * naming the property key and the known BPMN process IDs. BPMN process IDs are known
 * only after the adapters' <code>readBpmn</code>, so the check runs in the deployment
 * pipeline - identical on both platforms.
 */
@ExtendWith(SuppressOutputExtension.class)
public class UnknownWorkflowConfigurationTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("unknown-workflow/application.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingDeploymentEvents.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/first.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .setLogRecordPredicate(record -> record.getLevel().intValue() >= Level.WARNING.intValue())
      .assertLogRecords(records -> {
        final var messages = records
            .stream()
            .map(record -> record.getMessage() == null
                ? ""
                : String.format(record.getMessage(), record.getParameters()))
            .toList();
        Assertions.assertTrue(
            messages
                .stream()
                .anyMatch(message -> message
                    .contains("vanillabp.workflow-modules.test-module.workflows.NoSuchProcess") && message
                        .contains("'first'")),
            "expected the guiding WARN naming the unused workflow property key and the known BPMN process IDs but got: "
                + messages);
      });

  @Inject
  RecordingDeploymentEvents events;

  @Test
  @DisplayName("A configured workflow ID unknown to the BPMN resources boots with a guiding WARN")
  public void unknownConfiguredWorkflowIdBootsWithGuidingWarn() {

    // the module deployed and started normally - the unknown workflow ID is a WARN only
    final var recorded = events.getEvents();
    assertTrue(recorded.contains("adapter:demo1:deployResources:test-module"), recorded.toString());
    assertTrue(recorded.contains("adapter:demo1:startWorkflowProcessing:test-module"), recorded.toString());

  }

}
