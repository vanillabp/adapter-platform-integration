package io.vanillabp.integration.support;

/**
 * The wording every message about a missing BPMS adapter ends with.
 * <p>
 * A developer whose application has no adapter should not have to search for what to add.
 * But the list of adapters does not belong in compiled code either. Adapters are released
 * apart from VanillaBP, and a list in a JAR is out of date the day a new adapter appears.
 * So the message points at the wiki page with the complete list and the Maven coordinates.
 * <p>
 * The wording lives in this module because every workflow module depends on it. The two
 * messages which use it come from different places. This module reports an application
 * which has no VanillaBP integration at all (the adapter brings it). The Spring Boot
 * integration reports an application which has the integration but no adapter.
 */
public final class BpmsAdapters {

  private BpmsAdapters() {
  }

  /**
   * The wiki page listing every BPMS adapter available, with the artifact of each one.
   */
  public static final String ADAPTERS_WIKI_URL = "https://github.com/vanillabp/adapter-platform-integration/wiki/BPMS-adapters";

  /**
   * The part every message about a missing adapter ends with. It says where to look up an
   * adapter, that a migration needs more than one, and where the version comes from.
   *
   * @return The wording, starting on a new line
   */
  public static String artifactsToAdd() {

    return """

        Add a BPMS adapter as a dependency of your application. Which adapters exist, and the Maven \
        coordinates of each one, is listed at
          %s
        Adding several adapters is what migrating from one BPMS to another needs - VanillaBP runs \
        them side by side.
        Their versions are NOT managed by the VanillaBP BOM: BPMS adapters are released on their \
        own schedule, so name a version of your own."""
        .formatted(ADAPTERS_WIKI_URL);

  }

}
