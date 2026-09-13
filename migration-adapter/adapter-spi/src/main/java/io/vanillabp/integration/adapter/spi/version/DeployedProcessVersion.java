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
   * The separator between the version tag and the version the BPMS counted, which is how
   * version 1 of VanillaBP wrote a tagged version down.
   */
  public static final String VERSION_TAG_SEPARATOR = ":";

  /**
   * How an operator reads this version.
   * <p>
   * With a version tag the tag comes first and the version the BPMS counted follows it,
   * separated by a colon: a process tagged <code>release-7</code> and counted as the
   * fourth deployment reads <code>release-7:4</code>. Without a tag, and with a tag which
   * is blank, the counted version stands alone: <code>4</code>. There is no trailing
   * separator, so the two forms never look like one another.
   * <p>
   * Every caller showing a version to a person uses this, so a cockpit, a log line and a
   * support tool spell the same deployment the same way. See
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
