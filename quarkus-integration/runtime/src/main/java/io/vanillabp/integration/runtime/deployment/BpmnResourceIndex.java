package io.vanillabp.integration.runtime.deployment;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Build-time collected index of all BPMN resources and workflow modules of the
 * application, recorded as a synthetic CDI bean for the runtime deployment pipeline
 * (see {@link VanillaBpDeploymentRunner}).
 * <p>
 * The index is necessary because <code>resources-location</code> is RUN_TIME
 * configuration and a Quarkus fast-jar cannot pattern-scan
 * <code>**&#47;*.bpmn</code> at runtime (there is no equivalent of Spring's
 * <code>PathMatchingResourcePatternResolver</code>). Therefore all
 * <code>.bpmn</code> resource paths of all application archives are indexed at
 * build time as plain strings (relative to the classpath root) and filtered at
 * runtime by the configured location. Only classpath locations are supported on
 * Quarkus (resources are part of the application archives by definition of the
 * index).
 */
@Builder
@Getter
@Setter(AccessLevel.PACKAGE) // needed for object-serialization
@AllArgsConstructor
@NoArgsConstructor(access = AccessLevel.PACKAGE) // needed for object-serialization
public class BpmnResourceIndex {

  /**
   * The IDs of all workflow modules found in the application's archives at build
   * time. This mirrors Spring Boot's <code>WorkflowModules</code> bean: the
   * deployment pipeline runs for the modules detected in the classpath (modules
   * only <i>configured</i> but not in the classpath are reported by the
   * configuration validation).
   */
  private List<String> workflowModuleIds;

  /**
   * All <code>.bpmn</code> resource paths of all application archives, relative to
   * the classpath root (e.g. <code>processes/loan-approval/approval.bpmn</code>).
   */
  private List<String> bpmnResourcePaths;

  /**
   * Loads all indexed BPMN resources below the given resources location - the
   * Quarkus counterpart of the Spring Boot integration's pattern-resolver-based
   * loader. Keys keep subdirectories relative to the location, so same-named BPMN
   * files in different subdirectories do not overwrite each other. The returned
   * streams are owned and closed by the core deployment pipeline.
   *
   * @param resourcesLocation The configured resources location (a classpath
   *        location, e.g. <code>classpath:processes/loan-approval</code>)
   * @return A map of relative paths to BPMN resource input streams
   */
  public Map<String, InputStream> loadBpmnResources(
      final String resourcesLocation) {

    final var locationPrefix = normalize(resourcesLocation);

    final var result = new LinkedHashMap<String, InputStream>();
    final var classLoader = Thread.currentThread().getContextClassLoader();
    bpmnResourcePaths
        .stream()
        .filter(path -> path.startsWith(locationPrefix))
        .sorted() // deterministic pipeline order
        .forEach(path -> {
          final var bpmn = classLoader.getResourceAsStream(path);
          if (bpmn == null) {
            // indexed at build time but not on the runtime classpath - cannot
            // happen for regular builds, guard to fail understandably anyway
            throw new IllegalStateException(
                "BPMN resource '%s' was indexed at build time but is not available at runtime!"
                    .formatted(path));
          }
          result.put(path.substring(locationPrefix.length()), bpmn);
        });
    return result;

  }

  /**
   * Normalizes a configured resources location to a classpath-root-relative prefix:
   * Spring-style <code>classpath*:</code>/<code>classpath:</code> prefixes and a
   * leading slash are stripped, a trailing slash is enforced. Non-classpath
   * locations (e.g. <code>file:</code>) are rejected with a guiding message since
   * BPMN resources are indexed from the application archives at build time.
   *
   * @param resourcesLocation The configured resources location
   * @return The normalized prefix (always ending with a slash)
   */
  public static String normalize(
      final String resourcesLocation) {

    var location = resourcesLocation.trim();
    if (location.startsWith("classpath*:")) {
      location = location.substring("classpath*:".length());
    } else if (location.startsWith("classpath:")) {
      location = location.substring("classpath:".length());
    } else if (location.contains(":")) {
      throw new IllegalStateException(
          """
              The resources-location '%s' is not supported by the VanillaBP Quarkus integration! \
              BPMN resources are indexed from the application's archives at build time, so only \
              classpath locations are supported. Use 'classpath:<path>' (or a plain path)."""
              .formatted(resourcesLocation));
    }
    if (location.startsWith("/")) {
      location = location.substring(1);
    }
    if (!location.isEmpty() && !location.endsWith("/")) {
      location = location
          + "/";
    }
    return location;

  }

}
