package io.vanillabp.integration.test.config;

import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The configuration metadata of VanillaBP's own artifacts, for the tests which hold it.
 * <p>
 * Every framework jar ships files of the same two names, so a test reading the whole
 * classpath would be answered by somebody else's file. What counts as ours: a module of
 * this build writes into its own <code>target</code> directory, and a module which is
 * already released lies below the group's directory in a Maven repository.
 */
final class SpringConfigurationMetadata {

  /**
   * What the annotation processor writes while the main sources are compiled. It carries
   * the fields it found plus everything the hand-written file adds, so it is the file a
   * development environment reads.
   */
  static final String GENERATED = "META-INF/spring-configuration-metadata.json";

  /**
   * What somebody writes by hand, for the keys the processor cannot describe.
   */
  static final String HAND_WRITTEN = "META-INF/additional-spring-configuration-metadata.json";

  private SpringConfigurationMetadata() {

  }

  /**
   * Reads one of the two metadata files out of VanillaBP's artifacts.
   *
   * @param resource {@link #GENERATED} or {@link #HAND_WRITTEN}
   * @return What the <code>properties</code> arrays of those files hold, in the order they
   *         were read
   * @throws IOException If the classpath cannot be read
   */
  static List<JsonNode> propertiesOf(
      final String resource) throws IOException {

    final var mapper = new ObjectMapper();
    final var properties = new ArrayList<JsonNode>();
    for (final var url : ourResources(resource)) {
      try (var content = url.openStream()) {
        mapper
            .readTree(content)
            .path("properties")
            .forEach(properties::add);
      }
    }
    return properties;

  }

  /**
   * @param resource The name every module may bring
   * @return Where the classpath has it inside VanillaBP's own artifacts
   * @throws IOException If the classpath cannot be read
   */
  private static Collection<URL> ourResources(
      final String resource) throws IOException {

    final var ours = new ArrayList<URL>();
    for (final var url : Collections.list(classLoader().getResources(resource))) {
      final var location = url.toString();
      if (location.contains("/target/classes/") || location.contains("/io/vanillabp/")) {
        ours.add(url);
      }
    }
    return ours;

  }

  /**
   * @return The class loader the tests run in
   */
  private static ClassLoader classLoader() {

    return SpringConfigurationMetadata.class.getClassLoader();

  }

}
