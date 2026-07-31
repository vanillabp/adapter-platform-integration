package io.vanillabp.integration.runtime.deployment;

import java.util.List;

import io.quarkus.runtime.RuntimeValue;
import io.quarkus.runtime.annotations.Recorder;

/**
 * Records the build-time collected {@link BpmnResourceIndex} as a runtime object
 * (registered as a synthetic CDI bean by the deployment build steps).
 */
@Recorder
public class DeploymentRecorder {

  /**
   * @param workflowModuleIds The IDs of all workflow modules found at build time
   * @param bpmnResourcePaths All BPMN resource paths (relative to the classpath root)
   * @return The recorded index
   */
  public RuntimeValue<BpmnResourceIndex> recordBpmnResourceIndex(
      final List<String> workflowModuleIds,
      final List<String> bpmnResourcePaths) {

    return new RuntimeValue<>(BpmnResourceIndex
        .builder()
        .workflowModuleIds(workflowModuleIds)
        .bpmnResourcePaths(bpmnResourcePaths)
        .build());

  }

}
