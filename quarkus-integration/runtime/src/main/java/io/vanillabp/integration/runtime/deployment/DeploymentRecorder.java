package io.vanillabp.integration.runtime.deployment;

import java.util.List;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

/**
 * Records the build-time collected {@link BpmsResourceIndex} as a runtime object
 * (registered as a synthetic CDI bean by the deployment build steps).
 */
@Recorder
public class DeploymentRecorder {

  /**
   * Quarkus builds the recorder while it builds the application and hands it to the build
   * step which indexed the resources. What that step calls here is not executed but written
   * into the bytecode of the boot, and it runs when the application starts.
   */
  public DeploymentRecorder() {
  }

  /**
   * Hands the index of the build over to the runtime. Both lists are recorded as bytecode,
   * so they carry plain strings: a build-time object which knows how to read a resource
   * could not be written into the boot, and the paths can.
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
