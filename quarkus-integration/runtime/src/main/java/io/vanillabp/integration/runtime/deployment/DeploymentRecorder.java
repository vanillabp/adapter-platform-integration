package io.vanillabp.integration.runtime.deployment;

import java.util.List;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

/**
 * Records the {@link BpmsResourceIndex}, which is collected at build time, as a runtime
 * object. The build steps of the deployment module register it as a synthetic CDI bean.
 */
@Recorder
public class DeploymentRecorder {

  /**
   * Quarkus creates the recorder while it builds the application. It hands the recorder to
   * the build step which indexed the resources. What that step calls here does not run then.
   * It is written into the bytecode of the boot and runs when the application starts.
   */
  public DeploymentRecorder() {
  }

  /**
   * Hands the index of the build over to the runtime. Both lists are recorded as bytecode, so
   * they hold plain strings. A build-time object which knows how to read a resource could not
   * be written into the boot. The paths can.
   *
   * @param workflowModuleIds The IDs of all workflow modules found at build time
   * @param resourcePaths All indexed resource paths (relative to the classpath root)
   * @return The recorded index
   */
  public RuntimeValue<BpmsResourceIndex> recordBpmsResourceIndex(
      final List<String> workflowModuleIds,
      final List<String> resourcePaths) {

    return new RuntimeValue<>(BpmsResourceIndex
        .builder()
        .workflowModuleIds(workflowModuleIds)
        .resourcePaths(resourcePaths)
        .build());

  }

}
