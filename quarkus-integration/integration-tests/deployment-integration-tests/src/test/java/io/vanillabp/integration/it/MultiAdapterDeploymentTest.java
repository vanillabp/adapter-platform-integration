package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.test.deployment.Aggregate;
import io.vanillabp.integration.test.deployment.AggregatePersistence;
import io.vanillabp.integration.test.deployment.RecordingDeploymentEvents;
import io.vanillabp.integration.test.deployment.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Deployment pipeline with TWO configured adapter ids of the same type (the
 * migration scenario): each prioritized adapter deploys and starts processing, in
 * prioritized order - for BPMS migration all deployed adapters keep processing
 * workflows.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MultiAdapterDeploymentTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("multi-adapter/application.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingDeploymentEvents.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/first.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  RecordingDeploymentEvents events;

  private static void assertOrder(
      final List<String> events,
      final String earlier,
      final String later) {

    final var earlierIndex = events.indexOf(earlier);
    final var laterIndex = events.indexOf(later);
    assertTrue(earlierIndex != -1, "event '%s' was not recorded: %s".formatted(earlier, events));
    assertTrue(laterIndex != -1, "event '%s' was not recorded: %s".formatted(later, events));
    assertTrue(
        earlierIndex < laterIndex,
        "expected '%s' before '%s' but got: %s".formatted(earlier, later, events));

  }

  @Test
  @DisplayName("Every prioritized adapter deploys and starts processing, in prioritized order")
  public void bothAdaptersDeployAndStart() {

    final var recorded = events.getEvents();

    assertOrder(recorded, "adapter:demo1:deployResources:test-module", "adapter:demo2:deployResources:test-module");
    assertOrder(
        recorded,
        "adapter:demo1:startWorkflowProcessing:test-module",
        "adapter:demo2:startWorkflowProcessing:test-module");
    // deployment of ALL prioritized adapters happens before processing starts
    assertOrder(
        recorded,
        "adapter:demo2:deployResources:test-module",
        "adapter:demo1:startWorkflowProcessing:test-module");

  }

}
