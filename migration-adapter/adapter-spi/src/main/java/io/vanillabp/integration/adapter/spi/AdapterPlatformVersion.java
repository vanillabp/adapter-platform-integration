package io.vanillabp.integration.adapter.spi;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guards a BPMS adapter against a VanillaBP platform integration which is older than the
 * one it was built against.
 * <p>
 * Maven cannot detect this situation: a version given in the application's
 * <code>dependencyManagement</code> - which is what importing
 * <code>io.vanillabp:vanillabp-bom</code> does - always wins over the version an adapter
 * requires transitively, and it does so silently, even if that means a DOWNGRADE. The
 * build stays green and the mismatch surfaces at runtime as
 * <code>NoSuchMethodError</code> or <code>NoClassDefFoundError</code>, typically deep
 * inside the adapter. Therefore every adapter checks the platform version itself, at
 * startup, and reports a message naming the artifacts to raise.
 * <p>
 * The check has to be done by the ADAPTER and not by the platform integration: only the
 * adapter knows the platform version it was compiled against, and code of a too old
 * platform integration cannot contain a check introduced later.
 * <p>
 * Adapters call {@link #requireCompatiblePlatform(String, Class)} once per adapter type,
 * e.g. in the constructor of their {@link AdapterDeploymentService} implementation. The
 * required version is read from the adapter's own version descriptor
 * <code>META-INF/vanillabp/adapter-&lt;adapter-type&gt;.properties</code>, which the
 * adapter's build fills by resource filtering:
 *
 * <pre>
 * adapter.version=${project.version}
 * platform.version=${adapter-platform.version}
 * </pre>
 *
 * Versions are compared by their numeric parts only ("2.1.0" is newer than "2.0.7"), the
 * qualifier is ignored: <code>2.0.0-SNAPSHOT</code> satisfies a required
 * <code>2.0.0</code> so development builds work. Versions which cannot be parsed (e.g.
 * custom builds) are treated as compatible - the guard must never break a build it does
 * not understand.
 */
public final class AdapterPlatformVersion {

  private static final Logger log = LoggerFactory.getLogger(AdapterPlatformVersion.class);

  /**
   * Version descriptor of the platform integration itself, part of the
   * <code>vanillabp-adapter-spi</code> JAR.
   */
  private static final String PLATFORM_DESCRIPTOR = "/META-INF/vanillabp/platform-version.properties";

  /**
   * Version descriptor of an adapter, part of the adapter's core JAR.
   */
  private static final String ADAPTER_DESCRIPTOR = "/META-INF/vanillabp/adapter-%s.properties";

  private static final String UNKNOWN_VERSION = "unknown";

  /**
   * Adapter types checked successfully, to keep the check cheap for setups having several
   * adapter ids of the same type (migration scenarios).
   */
  private static final Set<String> ALREADY_CHECKED = ConcurrentHashMap.newKeySet();

  private static volatile String platformVersion;

  private AdapterPlatformVersion() {
    // static helper
  }

  /**
   * @return The version of the VanillaBP platform integration found on the classpath or
   *         <code>unknown</code> if it cannot be determined.
   */
  public static String platformVersion() {

    var result = platformVersion;
    if (result == null) {
      result = readProperty(AdapterPlatformVersion.class, PLATFORM_DESCRIPTOR, "platform.version");
      platformVersion = result == null ? UNKNOWN_VERSION : result;
      result = platformVersion;
    }
    return result;

  }

  /**
   * Fails if the VanillaBP platform integration on the classpath is older than the one
   * the given adapter was built against.
   *
   * @param adapterType The adapter's type (e.g. <code>camunda7</code>), used to find the
   *        adapter's version descriptor
   * @param adapterClass A class of the adapter's core module, used as the source of the
   *        class loader the descriptor is read from
   * @throws IllegalStateException If the platform integration is too old
   */
  public static void requireCompatiblePlatform(
      final String adapterType,
      final Class<?> adapterClass) {

    if (!ALREADY_CHECKED.add(adapterType)) {
      return;
    }

    final var descriptor = ADAPTER_DESCRIPTOR.formatted(adapterType);
    final var requiredPlatformVersion = readProperty(adapterClass, descriptor, "platform.version");
    if (requiredPlatformVersion == null) {
      // not an error: e.g. an adapter built without resource filtering
      log.debug("Adapter '{}' has no version descriptor '{}' - skipping platform version check", adapterType,
          descriptor);
      return;
    }

    final var foundPlatformVersion = platformVersion();
    if (isAtLeast(foundPlatformVersion, requiredPlatformVersion)) {
      log.debug("Adapter '{}' requires VanillaBP platform {} or newer, found {}", adapterType,
          requiredPlatformVersion, foundPlatformVersion);
      return;
    }

    final var adapterVersion = readProperty(adapterClass, descriptor, "adapter.version");
    ALREADY_CHECKED.remove(adapterType); // report again on the next attempt
    throw new IllegalStateException("""
        The VanillaBP '%s' adapter %s requires the VanillaBP platform integration %s or newer, \
        but %s was found on the classpath.
        Most likely your build pins the platform version to an older release, e.g. by importing \
        an older 'io.vanillabp:vanillabp-bom'. Maven does not report this as a conflict: a version \
        managed by your application always wins over the version required by the adapter, even if \
        that is a downgrade.
        To fix this raise 'io.vanillabp:vanillabp-bom' to %s or newer. If you manage the VanillaBP \
        versions without that BOM, raise all of 'io.vanillabp:vanillabp-integration-spi', \
        'io.vanillabp:vanillabp-adapter-spi', 'io.vanillabp.adapter:migration-adapter' and \
        the platform integration ('io.vanillabp:vanillabp-spring-boot-integration' respectively \
        'io.vanillabp:vanillabp-quarkus-integration') to %s or newer."""
        .formatted(
            adapterType,
            adapterVersion == null ? UNKNOWN_VERSION : adapterVersion,
            requiredPlatformVersion,
            foundPlatformVersion,
            requiredPlatformVersion,
            requiredPlatformVersion));

  }

  /**
   * Compares two versions by their numeric parts, ignoring the qualifier.
   *
   * @param actual The version found
   * @param required The version required
   * @return Whether <code>actual</code> is equal to or newer than <code>required</code>.
   *         Versions which cannot be parsed are reported as compatible.
   */
  static boolean isAtLeast(
      final String actual,
      final String required) {

    final var actualParts = numericParts(actual);
    final var requiredParts = numericParts(required);
    if ((actualParts == null) || (requiredParts == null)) {
      return true;
    }

    for (int i = 0; i < Math.max(actualParts.length, requiredParts.length); i++) {
      final var actualPart = i < actualParts.length ? actualParts[i] : 0;
      final var requiredPart = i < requiredParts.length ? requiredParts[i] : 0;
      if (actualPart != requiredPart) {
        return actualPart > requiredPart;
      }
    }
    return true;

  }

  /**
   * @param version A version like <code>2.1.0-SNAPSHOT</code>
   * @return The numeric parts of the version or <code>null</code> if it cannot be parsed
   */
  private static int[] numericParts(
      final String version) {

    if ((version == null) || version.isBlank()) {
      return null;
    }

    final var qualifierAt = version.indexOf('-');
    final var numeric = qualifierAt == -1 ? version : version.substring(0, qualifierAt);
    final var parts = numeric.split("\\.");
    final var result = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      try {
        result[i] = Integer.parseInt(parts[i]);
      } catch (final NumberFormatException e) {
        return null;
      }
    }
    return result;

  }

  private static String readProperty(
      final Class<?> owner,
      final String descriptor,
      final String key) {

    try (InputStream in = owner.getResourceAsStream(descriptor)) {
      if (in == null) {
        return null;
      }
      final var properties = new Properties();
      properties.load(in);
      final var value = properties.getProperty(key);
      return (value == null) || value.isBlank() ? null : value.trim();
    } catch (final IOException e) {
      log.debug("Could not read '{}'", descriptor, e);
      return null;
    }

  }

}
