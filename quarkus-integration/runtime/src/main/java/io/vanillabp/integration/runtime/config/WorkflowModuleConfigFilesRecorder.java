package io.vanillabp.integration.runtime.config;

import java.util.concurrent.atomic.AtomicReference;

import io.quarkus.runtime.annotations.Recorder;

/**
 * Says at startup what the build found out about the configuration files of the workflow
 * modules. Which files a workflow module ships can only be seen while the application is
 * built, and a developer reads the log of a start far more often than the log of a build,
 * so the sentence is written there.
 * <p>
 * A recorder runs before the beans of the application exist, so it cannot reach the
 * collection the whole start reports into. It keeps the report instead, and the
 * deployment runner hands it over once it has that collection - see
 * {@link #takeReport()}.
 */
@Recorder
public class WorkflowModuleConfigFilesRecorder {

  /**
   * Built by Quarkus while it records the build steps. A recorder keeps no state: it
   * carries what the build found out into the starting application, and Quarkus decides
   * when its methods run.
   */
  public WorkflowModuleConfigFilesRecorder() {
  }

  /**
   * What the build found out and nobody has picked up yet. Static because a recorder is
   * built by Quarkus and read by a bean, and the two never meet.
   */
  private static final AtomicReference<String> WAITING_REPORT = new AtomicReference<>();

  /**
   * Keeps the report until the start has somewhere to put it. Every line of it names a
   * file whose settings nobody reads.
   *
   * @param report What the build has to say about those files
   */
  public void report(
      final String report) {

    WAITING_REPORT.set(report);

  }

  /**
   * Hands the report over, once, to whoever can report it into the box of the start.
   *
   * @return What the build found out, or <code>null</code> where it found nothing or
   *         where the report was taken already
   */
  public static String takeReport() {

    return WAITING_REPORT.getAndSet(null);

  }

  /**
   * Ends the boot. A workflow module which ships the same file in two places has no answer
   * to which of the two applies, so the application says so instead of picking one.
   *
   * @param reason What the build found out about those files
   */
  public void refuseToStart(
      final String reason) {

    throw new IllegalStateException(reason);

  }

}
