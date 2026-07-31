package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
import io.vanillabp.integration.test.deployment.Aggregate;
import io.vanillabp.integration.test.deployment.AggregatePersistence;
import io.vanillabp.integration.test.deployment.EarlyWiringService;
import io.vanillabp.integration.test.deployment.NonMatchingWiringService;
import io.vanillabp.integration.test.deployment.RecordingDeploymentEvents;
import io.vanillabp.integration.test.deployment.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Acceptance test of the Quarkus runtime deployment pipeline (story 26b): at boot
 * the platform runs
 * <code>readBpmn &rarr; prepareBpmn &rarr; wireBpmn &rarr; deployResources &rarr;
 * startWorkflowProcessing</code> for the workflow module's BPMN resources - exactly
 * like the Spring Boot integration:
 * <ul>
 *   <li>all <code>.bpmn</code> files below the configured
 *       <code>resources-location</code> are found (incl. subdirectories, keys keep
 *       the relative path),</li>
 *   <li>extensions are wired after the adapter, ordered by
 *       <code>getOrder()</code>,</li>
 *   <li>extensions whose declared model/context types do not match the adapter are
 *       neither wired nor started,</li>
 *   <li>workflow processing of the adapter starts before the extensions'.</li>
 * </ul>
 */
@ExtendWith(SuppressOutputExtension.class)
public class DeploymentPipelineTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("pipeline/application.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingDeploymentEvents.class)
          .addClass(EarlyWiringService.class)
          .addClass(NonMatchingWiringService.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/first.bpmn")
          .addAsResource("bpmn/second.bpmn", "processes/dummy/sub/second.bpmn")
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
  @DisplayName("The pipeline runs in order per BPMN file, incl. files in subdirectories")
  public void pipelineRunsInOrder() {

    final var recorded = events.getEvents();

    // both files below the resources-location were found; the BPMN process id is
    // derived from the relative path by the dummy adapter, so subdirectories are
    // preserved
    assertOrder(recorded, "adapter:demo:readBpmn:test-module:first.bpmn",
        "adapter:demo:prepareBpmn:test-module:first.bpmn");
    assertOrder(recorded, "adapter:demo:prepareBpmn:test-module:first.bpmn", "adapter:demo:wireBpmn:test-module:first");
    assertOrder(
        recorded,
        "adapter:demo:readBpmn:test-module:sub/second.bpmn",
        "adapter:demo:wireBpmn:test-module:sub/second");

    // deployment happens after all files were wired, processing starts afterwards
    assertOrder(recorded, "adapter:demo:wireBpmn:test-module:first", "adapter:demo:deployResources:test-module");
    assertOrder(recorded, "adapter:demo:wireBpmn:test-module:sub/second", "adapter:demo:deployResources:test-module");
    assertOrder(recorded, "adapter:demo:deployResources:test-module",
        "adapter:demo:startWorkflowProcessing:test-module");

  }

  @Test
  @DisplayName("Extensions are wired after the adapter, ordered by getOrder()")
  public void extensionsWiredInOrder() {

    final var recorded = events.getEvents();

    // per BPMN process: adapter wiring first, then the extensions ordered by
    // getOrder() (EarlyWiringService = -10, dummy extension = 0)
    assertOrder(recorded, "adapter:demo:wireBpmn:test-module:first", "extensionEarly:wireBpmn:test-module:first");
    assertOrder(recorded, "extensionEarly:wireBpmn:test-module:first", "extension:wireBpmn:test-module:first");

    // extensions start after the adapter started, ordered by getOrder()
    assertOrder(
        recorded,
        "adapter:demo:startWorkflowProcessing:test-module",
        "extensionEarly:startWorkflowProcessing:test-module");
    assertOrder(
        recorded,
        "extensionEarly:startWorkflowProcessing:test-module",
        "extension:startWorkflowProcessing:test-module");

  }

  @Test
  @DisplayName("Extensions with non-matching model/context types are neither wired nor started")
  public void nonMatchingExtensionUntouched() {

    assertEquals(
        List.of(),
        events
            .getEvents()
            .stream()
            .filter(event -> event.startsWith("extensionNonMatching:"))
            .toList());

  }

}
