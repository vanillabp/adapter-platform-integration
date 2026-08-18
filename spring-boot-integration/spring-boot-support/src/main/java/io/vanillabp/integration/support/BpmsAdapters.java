package io.vanillabp.integration.support;

import java.util.List;

/**
 * The BPMS adapters an application may add, and the wording naming them (story 81).
 * <p>
 * A developer whose application has no adapter should not have to search for what to
 * add, so every message about a missing adapter names the artifacts. The list lives
 * here, in the module every workflow module depends on, because the two messages using
 * it come from different places: this module reports an application which has no
 * VanillaBP integration at all (the adapter brings it), the Spring Boot integration
 * reports an application which has the integration but no adapter.
 * <p>
 * Adapters are released independently of VanillaBP - the VanillaBP BOM deliberately does
 * not manage their versions - which is why the wording asks for a version.
 */
public final class BpmsAdapters {

  private BpmsAdapters() {
  }

  /**
   * The Spring Boot artifact of each BPMS adapter, with the BPMS it serves.
   */
  private static final List<String> SPRING_BOOT_ARTIFACTS = List
      .of(
          "org.camunda.community.vanillabp:camunda7-adapter-spring-boot (Camunda 7, engine embedded into the application)",
          "org.camunda.community.vanillabp:camunda8-adapter-spring-boot (Camunda 8, remote cluster)",
          "io.vanillabp:process-engine-api-adapter-spring-boot (BPM-Crafters Process-Engine-API)");

  /**
   * The part every message about a missing adapter ends with: the artifacts to choose
   * from, that more than one is what a migration needs, and where the version comes
   * from.
   *
   * @return The wording, starting on a new line
   */
  public static String artifactsToAdd() {

    return """

        Add one of these dependencies to your application:
          %s
        Adding several of them is what migrating from one BPMS to another needs - VanillaBP runs \
        them side by side.
        Their versions are NOT managed by the VanillaBP BOM: BPMS adapters are released on their \
        own schedule, so name a version of your own."""
        .formatted(String.join("\n  ", SPRING_BOOT_ARTIFACTS));

  }

}
