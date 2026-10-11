package io.vanillabp.integration.adapter.spi.version;

/**
 * What a delivery of a BPMS carries as its process version. An adapter which keeps no
 * {@link ProcessVersionCatalog} tells the core this (see
 * {@link io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskWiring#reportNoProcessVersionCatalog}).
 * <p>
 * If an adapter registers no catalog, the core sees two possible reasons. Either the BPMS
 * was not asked yet, or there is nothing to ask it. This enum says it is the second one.
 * Then the core can name the methods whose version will never match on that BPMS.
 * <p>
 * A BPMS which counts its versions, and which can be asked about them, registers a
 * catalog and never gets here. That is why there is no constant for a counted version.
 */
public enum ReportedProcessVersion {

  /**
   * Nothing. A method which names a version never runs on this BPMS, whatever the
   * version says.
   */
  NONE,

  /**
   * The version tag of the model the workflow runs on, if the engine fills it. Such a
   * delivery matches a version which names exactly that tag. It does not match a range.
   * To place a version in a range, you need the order in which the versions were
   * deployed, and only a catalog holds that order.
   */
  VERSION_TAG

}
