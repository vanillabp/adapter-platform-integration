package io.vanillabp.integration.test.nativeimage;

import java.util.List;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.configuration.ConfigUtils;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Reads back the configuration the two workflow modules of this application ship, once for
 * every place such a file may sit: at the classpath root and inside a directory named after
 * the workflow module ID, as YAML and as properties, in the application's own archive and in
 * a dependency JAR. Every one of them exists twice, plain and named after the profile
 * <code>tenant</code>, so the value read says which of the two files won.
 * <p>
 * On the JVM the profile variant wins wherever the profile is active. A native image carries
 * only the resources it was told about, so the same is true of a binary only because the
 * VanillaBP extension registers these files - which is what this check is here to notice.
 * <p>
 * The last key comes from a profile section of <code>application.yaml</code> rather than from
 * a workflow module. It is the way out the extension recommends for the values a separate
 * <code>application-tenant.yaml</code> would carry, and it is asserted here so that the
 * recommendation is measured rather than believed.
 */
@ApplicationScoped
public class WorkflowModuleConfigurationCheck {

  private static final String PROFILE_OF_THE_SECOND_FILE = "tenant";

  private static final String VALUE_OF_THE_PLAIN_FILE = "from-the-plain-file";

  private static final String VALUE_OF_THE_PROFILE_FILE = "from-the-tenant-file";

  private static final List<String> KEYS_THE_MODULES_CONFIGURE = List.of(
      "native-image-test.test-module-root-yaml",
      "native-image-test.test-module-root-properties",
      "native-image-test.test-module-subdirectory-yaml",
      "native-image-test.test-module-subdirectory-properties",
      "native-image-test.jar-module-root-yaml",
      "native-image-test.jar-module-root-properties",
      "native-image-test.jar-module-subdirectory-yaml",
      "native-image-test.jar-module-subdirectory-properties",
      "native-image-test.application-profile-section");

  /**
   * @return One line per key whose value is not the one the active profile asks for, empty if
   *         every file was read and the right one won
   */
  public List<String> whatTheConfigurationGotWrong() {

    final var expected = ConfigUtils.isProfileActive(PROFILE_OF_THE_SECOND_FILE)
        ? VALUE_OF_THE_PROFILE_FILE
        : VALUE_OF_THE_PLAIN_FILE;

    return KEYS_THE_MODULES_CONFIGURE
        .stream()
        .flatMap(key -> {
          final var configured = ConfigProvider
              .getConfig()
              .getOptionalValue(key, String.class)
              .orElse("<not configured at all>");
          return configured.equals(expected)
              ? Stream.empty()
              : Stream.of("'%s' is '%s' instead of '%s'".formatted(key, configured, expected));
        })
        .toList();

  }

  /**
   * @return The profiles active, to be reported next to what was read
   */
  public String activeProfiles() {

    return String.join(",", ConfigUtils.getProfiles());

  }

}
