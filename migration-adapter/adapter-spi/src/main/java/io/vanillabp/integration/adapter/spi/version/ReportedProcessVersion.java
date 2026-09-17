package io.vanillabp.integration.adapter.spi.version;

/**
 * What a delivery of a BPMS carries as its process version, told by an adapter which
 * keeps no {@link ProcessVersionCatalog} (see
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#reportNoProcessVersionCatalog}).
 * <p>
 * An adapter which registers no catalog leaves the core with two readings: the BPMS was
 * not asked yet, or there is nothing to ask it. This is the second reading, and it is
 * what lets the core name the methods whose version will never be met on that BPMS.
 * <p>
 * A BPMS which counts its versions and can be asked about them registers a catalog and
 * never gets here, which is why there is no constant for a counted version.
 */
public enum ReportedProcessVersion {

  /**
   * Nothing. A method naming a version never runs on this BPMS, whatever the version
   * says.
   */
  NONE,

  /**
   * The version tag of the model the workflow runs on, where the engine fills it. A
   * version naming exactly that tag is met by such a delivery. A range is not: placing
   * a version in a range needs the order the versions were deployed in, and that order
   * is what a catalog holds.
   */
  VERSION_TAG

}
