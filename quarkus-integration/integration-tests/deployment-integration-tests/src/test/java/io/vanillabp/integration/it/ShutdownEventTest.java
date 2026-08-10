package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.deployment.Aggregate;
import io.vanillabp.integration.test.deployment.AggregatePersistence;
import io.vanillabp.integration.test.deployment.FileRecordingStopListener;
import io.vanillabp.integration.test.deployment.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Proves the REAL {@code ShutdownEvent} path: on graceful shutdown (the test
 * application being undeployed) workflow processing is stopped in reverse start
 * order - extensions first, then the adapter. The stop events are recorded into a
 * file because the assertions run after the application was undeployed (no bean
 * access anymore).
 */
@ExtendWith(SuppressOutputExtension.class)
public class ShutdownEventTest {

  private static final Path STOP_EVENTS_FILE = Path
      .of("target", "shutdown-event-test", "stop-events.txt")
      .toAbsolutePath();

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setBeforeAllCustomizer(() -> {
        try {
          Files.deleteIfExists(STOP_EVENTS_FILE);
        } catch (final IOException e) {
          throw new UncheckedIOException(e);
        }
      })
      .withApplicationRoot(jar -> jar
          .addAsResource("pipeline/application.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(FileRecordingStopListener.class)
          .addAsResource("bpmn/first.bpmn", "processes/dummy/first.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey(FileRecordingStopListener.PROPERTY_STOP_EVENTS_FILE, STOP_EVENTS_FILE.toString())
      .setAfterUndeployListener(() -> {
        final List<String> lines;
        try {
          lines = Files.readAllLines(STOP_EVENTS_FILE, StandardCharsets.UTF_8);
        } catch (final IOException e) {
          throw new AssertionError(
              "expected stop events to be recorded on shutdown at "
                  + STOP_EVENTS_FILE, e);
        }
        final var extensionIndex = lines.indexOf("extension:stopWorkflowProcessing:test-module");
        final var adapterIndex = lines.indexOf("adapter:demo:stopWorkflowProcessing:test-module");
        assertTrue(
            extensionIndex != -1,
            "expected the dummy extension to be stopped on shutdown but got: "
                + lines);
        assertTrue(
            adapterIndex != -1,
            "expected the dummy adapter to be stopped on shutdown but got: "
                + lines);
        assertTrue(
            extensionIndex < adapterIndex,
            "expected extensions to be stopped BEFORE adapters but got: "
                + lines);
      });

  @Test
  @DisplayName("On shutdown workflow processing is stopped in reverse order (asserted after undeploy)")
  public void shutdownStopsProcessing() {
    // the assertions run in the afterUndeployListener above, after the
    // application was gracefully shut down
  }

}
