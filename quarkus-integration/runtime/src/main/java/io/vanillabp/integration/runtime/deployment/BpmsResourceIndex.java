package io.vanillabp.integration.runtime.deployment;

import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * An index of all BPMS resources and workflow modules of the application. It is collected at
 * build time and recorded as a synthetic CDI bean for the deployment pipeline at runtime (see
 * {@link VanillaBpDeploymentRunner}).
 * <p>
 * The index is needed because <code>resources-location</code> is RUN_TIME configuration.
 * And a Quarkus fast-jar cannot search for a pattern like <code>**&#47;*.bpmn</code> at
 * runtime. There is no counterpart of Spring's <code>PathMatchingResourcePatternResolver</code>.
 * So the resource paths of all application archives are indexed at build time, as plain
 * strings relative to the classpath root. At runtime, they are filtered by the configured
 * location and the file extension asked for. This finds the BPMN files of a workflow module
 * and the decision tables next to them. Quarkus supports only classpath locations, because
 * the index holds only resources of the application archives.
 */
@Builder
@Getter
@AllArgsConstructor
public class BpmsResourceIndex {

  /**
   * An index without content. The index is a synthetic <code>&#64;ApplicationScoped</code>
   * bean. Quarkus creates a client proxy for such a bean, which extends this class and calls a
   * constructor without arguments. That is why this constructor exists. Nothing else uses it.
   * The instance which a boot works with comes from the builder of this class, in
   * {@link DeploymentRecorder#recordBpmsResourceIndex(List, List)}.
   */
  BpmsResourceIndex() {
  }

  /**
   * The IDs of all workflow modules found in the application's archives at build time. This
   * matches Spring Boot's <code>WorkflowModules</code> bean. The deployment pipeline runs for
   * the modules found in the classpath. The configuration check reports modules which are
   * only <i>configured</i> but not in the classpath.
   */
  private List<String> workflowModuleIds;

  /**
   * The indexed resource paths of all application archives, relative to the classpath root
   * (e.g. <code>processes/loan-approval/approval.bpmn</code>). These are the BPMN files and
   * the DMN files.
   */
  private List<String> resourcePaths;

  /**
   * Loads all indexed resources with the given file extension below the given resources
   * location. It is the Quarkus counterpart of the Spring Boot integration's loader, which
   * uses a pattern resolver. The keys keep the subdirectories relative to the location. So
   * files with the same name in different subdirectories do not overwrite each other. The
   * deployment pipeline of the core owns the returned streams and closes them.
   *
   * @param resourcesLocation The configured resources location (a classpath
   *        location, e.g. <code>classpath:processes/loan-approval</code>)
   * @param extension The file extension asked for, e.g. <code>.bpmn</code>
   * @return A map of relative paths to resource input streams
   */
  public Map<String, InputStream> loadResources(
      final String resourcesLocation,
      final String extension) {

    final var locationPrefix = normalize(resourcesLocation);

    final var result = new LinkedHashMap<String, InputStream>();
    final var classLoader = Thread.currentThread().getContextClassLoader();
    resourcePaths
        .stream()
        .filter(path -> path.startsWith(locationPrefix))
        .filter(path -> path.endsWith(extension))
        .sorted() // deterministic pipeline order
        .forEach(path -> {
          final var resource = classLoader.getResourceAsStream(path);
          if (resource == null) {
            // Indexed at build time, but not on the runtime classpath. This cannot
            // happen in a normal build. The check is here to fail with a clear message.
            throw new IllegalStateException(
                "Resource '%s' was indexed at build time but is not available at runtime!"
                    .formatted(path));
          }
          result.put(path.substring(locationPrefix.length()), resource);
        });
    return result;

  }

  /**
   * Turns a configured resources location into a prefix relative to the classpath root. It
   * removes the Spring-style prefixes <code>classpath*:</code> and <code>classpath:</code>
   * and a leading slash. It makes sure that there is a trailing slash. Other locations
   * (e.g. <code>file:</code>) are refused with a guiding message. The reason: the resources
   * are indexed from the application archives at build time.
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
              BPMN and DMN resources are indexed from the application's archives at build time, so \
              only classpath locations are supported. Use 'classpath:<path>' (or a plain path)."""
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
