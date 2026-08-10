package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.logging.Level;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.deployment.Aggregate;
import io.vanillabp.integration.test.deployment.AggregatePersistence;
import io.vanillabp.integration.test.deployment.FailDeploymentListener;
import io.vanillabp.integration.test.deployment.RecordingDeploymentEvents;
import io.vanillabp.integration.test.deployment.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Deployment-failure policy on Quarkus, <code>warn</code> case (identical to Spring
 * Boot): a NON-first-priority adapter configured with
 * <code>vanillabp.adapters.&lt;id&gt;.deployment-failure: warn</code> whose
 * deployment fails does not prevent the boot - the failure is logged as a guiding
 * WARN and the adapter's workflow processing is not started, while the
 * first-priority adapter deploys and starts normally.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DeploymentFailureWarnTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("failure-warn/application.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingDeploymentEvents.class)
          .addClass(FailDeploymentListener.class)
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
                    .contains("Deployment of workflow module 'test-module' failed for adapter 'demo2'") && message
                        .contains("deployment-failure")),
            "expected the guiding deployment-failure warning but got: "
                + messages);
      });

  @Inject
  RecordingDeploymentEvents events;

  @Test
  @DisplayName("A failing non-first-priority adapter with policy 'warn' does not prevent the boot")
  public void failingWarnAdapterDoesNotPreventBoot() {

    final var recorded = events.getEvents();

    // the first-priority adapter deployed and started normally
    assertTrue(recorded.contains("adapter:demo1:deployResources:test-module"), recorded.toString());
    assertTrue(recorded.contains("adapter:demo1:startWorkflowProcessing:test-module"), recorded.toString());

    // the failing adapter's pipeline ran (deployResources itself may be missing
    // from the recording: the CDI listener order between the recording and the
    // failure-injecting listener is undefined, so the failure may strike before
    // the call was recorded - the WARN assertion above proves the deployment
    // attempt) but its processing never started
    assertTrue(recorded.contains("adapter:demo2:readBpmn:test-module:first.bpmn"), recorded.toString());
    assertEquals(
        List.of(),
        recorded
            .stream()
            .filter("adapter:demo2:startWorkflowProcessing:test-module"::equals)
            .toList());

  }

}
