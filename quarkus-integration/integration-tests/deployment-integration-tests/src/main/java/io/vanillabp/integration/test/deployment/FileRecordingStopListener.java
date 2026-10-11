package io.vanillabp.integration.test.deployment;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.eclipse.microprofile.config.ConfigProvider;

import io.vanillabp.bpmsdouble.DummyDeploymentListener;
import io.vanillabp.extension.dummy.runtime.DummyExtensionListener;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Records <code>stopWorkflowProcessing</code> calls into the file configured by
 * <code>test.stop-events-file</code>. A test needs it to check the REAL
 * {@code ShutdownEvent} path. The shutdown happens when the test application is
 * undeployed, so after all test methods ran. The checks run in the test's
 * {@code afterUndeployListener}. That listener cannot reach the beans of the undeployed
 * application. So the events are handed over through the file system.
 */
@ApplicationScoped
public class FileRecordingStopListener implements DummyDeploymentListener, DummyExtensionListener {

  public static final String PROPERTY_STOP_EVENTS_FILE = "test.stop-events-file";

  @Override
  public void onPipelineCall(
      final String adapterId,
      final String method,
      final String workflowModuleId,
      final String detail) {

    if ("stopWorkflowProcessing".equals(method)) {
      record("adapter:%s:stopWorkflowProcessing:%s".formatted(adapterId, workflowModuleId));
    }

  }

  @Override
  public void onPipelineCall(
      final String method,
      final String workflowModuleId,
      final String detail) {

    if ("stopWorkflowProcessing".equals(method)) {
      record("extension:stopWorkflowProcessing:%s".formatted(workflowModuleId));
    }

  }

  private void record(
      final String event) {

    final var file = ConfigProvider
        .getConfig()
        .getOptionalValue(PROPERTY_STOP_EVENTS_FILE, String.class)
        .orElse(null);
    if (file == null) {
      return;
    }
    try {
      final var path = Path.of(file);
      Files.createDirectories(path.getParent());
      Files.writeString(
          path,
          event + System.lineSeparator(),
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }

  }

}
