package io.vanillabp.integration.runtime.support;

/**
 * The wording the message about missing BPMS adapter extensions ends with.
 * <p>
 * A developer whose application has no adapter should not have to search for what to add.
 * But the list of adapters does not belong in compiled code either. Adapters are released
 * apart from VanillaBP, so a list in a JAR is out of date on the day a new adapter appears.
 * So the message points at the wiki page. It has the complete list, with the name of each
 * Quarkus extension.
 * <p>
 * It says the same as its Spring Boot counterpart in
 * <code>vanillabp-spring-boot-support</code>. The two are one message in two wordings.
 */
public final class BpmsAdapters {

  private BpmsAdapters() {
  }

  /**
   * The wiki page listing every BPMS adapter available, with the Quarkus extension of each
   * one.
   */
  public static final String ADAPTERS_WIKI_URL = "https://github.com/vanillabp/adapter-platform-integration/wiki/BPMS-adapters";

  /**
   * The end of the message about a missing adapter. It says where to look up an adapter, that
   * a migration needs more than one, and where the version comes from.
   *
   * @return The wording, starting on a new line
   */
  public static String extensionsToAdd() {

    return """

        Add a BPMS adapter extension to your application. Which adapters exist, and the name of the \
        Quarkus extension of each one, is listed at
          %s
        Adding several adapters is what migrating from one BPMS to another needs - VanillaBP runs \
        them side by side.
        Their versions are NOT managed by the VanillaBP BOM: BPMS adapters are released on their \
        own schedule, so name a version of your own."""
        .formatted(ADAPTERS_WIKI_URL);

  }

}
