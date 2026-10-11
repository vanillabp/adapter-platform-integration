package io.vanillabp.integration.adapter.spi.version;

import java.time.Instant;

/**
 * One deployed version of a BPMN process as the BPMS counts it.
 * <p>
 * The <code>version</code> is the identifier the BPMS reports at runtime (in
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext#getProcessVersion()}
 * and the similar methods). For Camunda 7 and Camunda 8 it is an integer, counted
 * upwards per BPMN process id. The <code>versionTag</code> is the name the modeller gave
 * that version (<code>camunda:versionTag</code> or <code>zeebe:versionTag</code>), or
 * <code>null</code>. More than one version may use the same tag.
 * <p>
 * <code>deployedAt</code> orders versions whose identifiers are not numbers. Adapters of
 * a BPMS which counts versions upwards may leave it <code>null</code>. Counting upwards
 * IS the order of deployment, and the core then compares by that.
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
   * The separator between the version tag and the version the BPMS counted. Version 1 of
   * VanillaBP wrote a tagged version down this way.
   */
  public static final String VERSION_TAG_SEPARATOR = ":";

  /**
   * How an operator reads this version.
   * <p>
   * With a version tag, the tag comes first, then a colon, then the version the BPMS
   * counted. A process tagged <code>release-7</code> and counted as the fourth deployment
   * reads <code>release-7:4</code>. Without a tag, or with a blank tag, the counted
   * version stands alone: <code>4</code>. There is no trailing separator, so the two
   * forms never look alike.
   * <p>
   * Every caller which shows a version to a person uses this. So a cockpit, a log line
   * and a support tool write the same deployment the same way. See
   * <code>DeployedProcessVersionTest</code>.
   *
   * @return The version as an operator reads it
   */
  public String displayVersion() {

    return (versionTag == null) || versionTag.isBlank()
        ? version
        : versionTag + VERSION_TAG_SEPARATOR + version;

  }

  /**
   * A version of a BPMS which counts versions upwards, without a version tag.
   *
   * @param version The version identifier
   * @return The version
   */
  public static DeployedProcessVersion of(
      final String version) {

    return new DeployedProcessVersion(version, null, null);

  }

  /**
   * A version of a BPMS which counts versions upwards.
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
