package io.vanillabp.integration.runtime.config;

import io.quarkus.runtime.annotations.Recorder;
import lombok.extern.slf4j.Slf4j;

/**
 * Says at startup what the build found out about the configuration files of the workflow
 * modules. Which files a workflow module ships can only be seen while the application is
 * built, and a developer reads the log of a start far more often than the log of a build,
 * so the sentence is written there.
 */
@Slf4j
@Recorder
public class WorkflowModuleConfigFilesRecorder {

  /**
   * @param report What the build has to say about those files
   */
  public void report(
      final String report) {

    log.warn(report);

  }

}
