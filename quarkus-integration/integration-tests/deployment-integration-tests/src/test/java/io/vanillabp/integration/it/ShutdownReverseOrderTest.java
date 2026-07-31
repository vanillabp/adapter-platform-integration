package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.runtime.deployment.VanillaBpDeploymentRunner;
import io.vanillabp.integration.test.deployment.Aggregate;
import io.vanillabp.integration.test.deployment.AggregatePersistence;
import io.vanillabp.integration.test.deployment.EarlyWiringService;
import io.vanillabp.integration.test.deployment.RecordingDeploymentEvents;
import io.vanillabp.integration.test.deployment.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Stopping the deployment pipeline reverses the start order (like Spring Boot's
 * <code>SmartLifecycle.stop()</code>): extensions are stopped first - in reverse
 * wiring order - then the adapters; a second stop is a no-op (the runner is
 * idempotent, so the real {@code ShutdownEvent} after this test causes no second
 * round). The {@code ShutdownEvent} wiring itself is proven by
 * {@link ShutdownEventTest}.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ShutdownReverseOrderTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("pipeline/application.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingDeploymentEvents.class)
          .addClass(EarlyWiringService.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/first.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  RecordingDeploymentEvents events;

  @Inject
  VanillaBpDeploymentRunner runner;

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
  @DisplayName("Stop reverses the start order (extensions in reverse wiring order first, then adapters) and is idempotent")
  public void stopReversesStartOrderAndIsIdempotent() {

    runner.stop();

    final var recorded = events.getEvents();

    // start order was: adapter, extensionEarly (order -10), dummy extension (order 0);
    // stop order is the exact reverse
    assertOrder(recorded, "extension:stopWorkflowProcessing:test-module",
        "extensionEarly:stopWorkflowProcessing:test-module");
    assertOrder(recorded, "extensionEarly:stopWorkflowProcessing:test-module",
        "adapter:demo:stopWorkflowProcessing:test-module");
    // ...and everything stops after everything started
    assertOrder(recorded, "extension:startWorkflowProcessing:test-module",
        "extension:stopWorkflowProcessing:test-module");

    // a second stop must not notify anybody again
    runner.stop();
    assertEquals(recorded, events.getEvents());

  }

}
