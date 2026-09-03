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
