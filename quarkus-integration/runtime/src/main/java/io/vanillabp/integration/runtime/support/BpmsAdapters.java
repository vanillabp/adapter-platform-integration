package io.vanillabp.integration.runtime.support;

import java.util.List;

/**
 * The BPMS adapters a Quarkus application may add, and the wording naming them
 * (story 81).
 * <p>
 * A developer whose application has no adapter should not have to search for what to add,
 * so the message about missing adapter extensions names the artifacts. It says the same
 * as its Spring Boot counterpart in <code>vanillabp-spring-boot-support</code> - the two
 * are one message with two wordings.
 * <p>
 * Adapters are released independently of VanillaBP - the VanillaBP BOM deliberately does
 * not manage their versions - which is why the wording asks for a version.
 */
public final class BpmsAdapters {

  private BpmsAdapters() {
  }

  /**
   * The Quarkus extension of each BPMS adapter, with the BPMS it serves.
   */
  private static final List<String> QUARKUS_EXTENSIONS = List
      .of(
          "org.camunda.community.vanillabp:camunda7-adapter-quarkus (Camunda 7, engine embedded into the application)",
          "org.camunda.community.vanillabp:camunda8-adapter-quarkus (Camunda 8, remote cluster)",
          "io.vanillabp:process-engine-api-adapter-quarkus (BPM-Crafters Process-Engine-API)");

  /**
   * The part the message about a missing adapter ends with: the extensions to choose
   * from, that more than one is what a migration needs, and where the version comes from.
   *
   * @return The wording, starting on a new line
   */
  public static String extensionsToAdd() {

    return """

        Add one of these extensions to your application:
          %s
        Adding several of them is what migrating from one BPMS to another needs - VanillaBP runs \
        them side by side.
        Their versions are NOT managed by the VanillaBP BOM: BPMS adapters are released on their \
        own schedule, so name a version of your own."""
        .formatted(String.join("\n  ", QUARKUS_EXTENSIONS));

  }

}
