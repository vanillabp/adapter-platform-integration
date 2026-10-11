package io.vanillabp.integration.test.nativeimage;

import java.util.List;
import java.util.stream.Stream;

import org.eclipse.microprofile.config.ConfigProvider;

import io.quarkus.runtime.configuration.ConfigUtils;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Reads back the configuration which the two workflow modules of this application ship.
 * It reads once for every place where such a file may sit: at the classpath root, in a
 * <code>config</code> directory, inside a directory named after the workflow module ID,
 * and in a <code>config</code> directory inside that one. The places are split over the two
 * modules, because the same file may lie in one place only. They are also split over both
 * formats and both archives: the application's own and a dependency JAR. Every file exists
 * twice, plain and named after the profile <code>tenant</code>. So the value read says
 * which of the two won.
 * <p>
 * On the JVM, the profile variant wins wherever the profile is active. A native image
 * carries only the resources it was told about. So a binary behaves the same only because
 * the VanillaBP extension registers these files. This check is here to notice if it does
 * not.
 * <p>
 * The last key comes from a profile section of <code>application.yaml</code>, not from a
 * workflow module. The extension recommends this way out for the values which a separate
 * <code>application-tenant.yaml</code> would carry. It is asserted here, so the
 * recommendation is measured and not just believed.
 */
@ApplicationScoped
public class WorkflowModuleConfigurationCheck {

  private static final String PROFILE_OF_THE_SECOND_FILE = "tenant";

  private static final String VALUE_OF_THE_PLAIN_FILE = "from-the-plain-file";

  private static final String VALUE_OF_THE_PROFILE_FILE = "from-the-tenant-file";

  private static final List<String> KEYS_THE_MODULES_CONFIGURE = List.of(
      "native-image-test.test-module-root-yaml",
      "native-image-test.test-module-config-properties",
      "native-image-test.jar-module-subdirectory-yaml",
      "native-image-test.jar-module-config-properties",
      "native-image-test.application-profile-section");

  /**
   * @return One line per key whose value is not the one the active profile asks for. Empty
   *         if every file was read and the right one won
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
