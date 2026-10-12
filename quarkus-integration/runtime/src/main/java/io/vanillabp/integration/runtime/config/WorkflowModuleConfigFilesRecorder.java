package io.vanillabp.integration.runtime.config;

import java.util.concurrent.atomic.AtomicReference;

import io.quarkus.runtime.annotations.Recorder;

/**
 * Says at startup what the build found out about the configuration files of the workflow
 * modules. Which files a workflow module ships can only be seen while the application is
 * built. But a developer reads the log of a start far more often than the log of a build. So
 * the sentence goes into the log of the start.
 * <p>
 * A recorder runs before the beans of the application exist. So it cannot reach the
 * collection which the whole start reports into. Instead, it keeps the report. The
 * deployment runner hands it over once it has that collection. See {@link #takeReport()}.
 */
@Recorder
public class WorkflowModuleConfigFilesRecorder {

  /**
   * Quarkus creates it while it records the build steps. A recorder keeps no state. It
   * carries what the build found out into the starting application. Quarkus decides when its
   * methods run.
   */
  public WorkflowModuleConfigFilesRecorder() {
  }

  /**
   * What the build found out and nobody has picked up yet. It is static, because Quarkus
   * creates the recorder and a bean reads the report. The two never meet.
   */
  private static final AtomicReference<String> WAITING_REPORT = new AtomicReference<>();

  /**
   * Keeps the report until the start has a place to put it. Every line of it names a file
   * whose settings nobody reads.
   *
   * @param report What the build has to say about those files
   */
  public void report(
      final String report) {

    WAITING_REPORT.set(report);

  }

  /**
   * Hands the report over, once, to the one who can put it into the block of the start.
   *
   * @return What the build found out, or <code>null</code> where it found nothing or
   *         where the report was taken already
   */
  public static String takeReport() {

    return WAITING_REPORT.getAndSet(null);

  }

  /**
   * Ends the boot. A workflow module may ship the same file in two places. Then there is no
   * answer to which of the two applies. So the application says so and does not pick one.
   *
   * @param reason What the build found out about those files
   */
  public void refuseToStart(
      final String reason) {

    throw new IllegalStateException(reason);

  }

}
