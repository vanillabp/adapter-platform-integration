package io.vanillabp.integration.adapter.spi.version;

import java.time.Instant;

/**
 * One deployed version of a BPMN process as the BPMS counts it.
 * <p>
 * The <code>version</code> is the identifier the BPMS reports at runtime (in
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getProcessVersion()}
 * and its siblings) - for Camunda 7 and Camunda 8 an integer counted upwards per
 * BPMN process id. The <code>versionTag</code> is the name the modeller gave that
 * version (<code>camunda:versionTag</code> respectively <code>zeebe:versionTag</code>)
 * or <code>null</code>, and the same tag may be used by more than one version.
 * <p>
 * <code>deployedAt</code> orders versions whose identifiers are not numbers. Adapters
 * of a BPMS counting versions upwards may leave it <code>null</code>: counting upwards
 * IS the deployment order, which is what the core compares by then.
 *
 * @param version The version identifier the BPMS reports at runtime
 * @param versionTag The version tag of that version or <code>null</code>
 * @param deployedAt When that version was deployed or <code>null</code>
 */
public record DeployedProcessVersion(
                                     String version,
                                     String versionTag,
                                     Instant deployedAt) {

  /**
   * A version of a BPMS counting versions upwards, without a version tag.
   *
   * @param version The version identifier
   * @return The version
   */
  public static DeployedProcessVersion of(
      final String version) {

    return new DeployedProcessVersion(version, null, null);

  }

  /**
   * A version of a BPMS counting versions upwards.
   *
   * @param version The version identifier
   * @param versionTag The version tag or <code>null</code>
   * @return The version
   */
  public static DeployedProcessVersion of(
      final String version,
      final String versionTag) {

    return new DeployedProcessVersion(version, versionTag, null);

  }

}
