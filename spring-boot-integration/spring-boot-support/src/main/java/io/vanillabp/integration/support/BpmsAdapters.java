package io.vanillabp.integration.support;

/**
 * The wording every message about a missing BPMS adapter ends with (story 81).
 * <p>
 * A developer whose application has no adapter should not have to search for what to add,
 * but the list of adapters does not belong into compiled code either: adapters are
 * released independently of VanillaBP, and a list in a JAR is out of date the day a new
 * adapter appears. So the message points at the wiki page which carries the complete list
 * including the Maven coordinates.
 * <p>
 * The wording lives in this module, the one every workflow module depends on, because the
 * two messages using it come from different places: this module reports an application
 * which has no VanillaBP integration at all (the adapter brings it), the Spring Boot
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
   * The part every message about a missing adapter ends with: where to look up an
   * adapter, that more than one is what a migration needs, and where the version comes
   * from.
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
