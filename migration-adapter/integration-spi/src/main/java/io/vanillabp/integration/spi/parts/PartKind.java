package io.vanillabp.integration.spi.parts;

/**
 * The kinds of parts an application plugs into VanillaBP.
 *
 * @see VanillaBpParts
 */
public enum PartKind {

  /**
   * A BPMS adapter, named by its adapter type (e.g. <code>camunda7</code>).
   */
  ADAPTER,

  /**
   * An extension, named by itself (e.g. <code>business-cockpit</code>).
   */
  EXTENSION;

  /**
   * The kind in lower case. The same word stands in the messages a developer reads and in
   * the path {@link VanillaBpParts#descriptorOf(PartKind, String)} builds, so the two can
   * never drift apart.
   *
   * @return The word used in messages and in the name of the version descriptor
   */
  public String word() {

    return name().toLowerCase();

  }

}
