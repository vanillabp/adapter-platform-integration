package io.vanillabp.integration.spi.parts;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps an application from starting whose VanillaBP parts do not belong together.
 * <p>
 * An application puts together artifacts of several release cycles: the platform
 * integration, one or more BPMS adapters, and the extensions it uses. Each of them is
 * released at its own pace, so a dependency update can easily leave a pair behind which
 * was never built and never tested together. Neither Maven nor Gradle reports that: a
 * version the application manages always wins over the version a part asks for
 * transitively, silently and even if that means a downgrade. The build stays green, and
 * the mismatch shows up while the application runs, as a
 * <code>NoSuchMethodError</code> or a <code>NoClassDefFoundError</code> in a place which
 * seems to have nothing to do with the update.
 * <p>
 * Therefore every part says which platform integration it was built against, the platform
 * says which parts it still serves, and the boot compares the two. What the rule is and
 * why is decision 71 in the repository's <code>DECISIONS.md</code>.
 *
 * <h2>The version descriptor</h2>
 *
 * Every part ships a properties file, filled by its own build through resource filtering:
 *
 * <pre>
 * META-INF/vanillabp/adapter-camunda7.properties      (a BPMS adapter)
 * META-INF/vanillabp/extension-business-cockpit.properties   (an extension)
 *
 * part.version=${project.version}
 * part.artifact=${project.groupId}:${project.artifactId}
 * platform.version=${adapter-platform.version}
 * </pre>
 *
 * The name carries the part into the file name, which keeps the descriptors apart when
 * several adapters are on the classpath. That is the normal case while an application
 * migrates from one BPMS to another.
 * <p>
 * A properties file is read, not scanned for: it survives a Spring Boot fat jar, a shaded
 * jar and the native image of Quarkus, as long as the resource is registered. The Quarkus
 * extension registers it for every part it finds while building.
 *
 * <h2>Who asks</h2>
 *
 * The core judges every adapter and every extension it knows at startup, which is the one
 * place the rule lives. An adapter asks as well, by calling
 * {@link #requireAdapterFitsPlatform(String, Class)} in the constructor of its deployment
 * service. That call is not a repetition: code of a platform integration which is older
 * than the check cannot contain the check, so the only part able to report a platform
 * which is too old is the part itself.
 */
public final class VanillaBpParts {

  private static final Logger log = LoggerFactory.getLogger(VanillaBpParts.class);

  /**
   * Version descriptor of the platform integration itself, part of the
   * <code>vanillabp-integration-spi</code> JAR.
   */
  public static final String PLATFORM_DESCRIPTOR = "META-INF/vanillabp/platform-version.properties";

  /**
   * Version descriptor of a part, part of the JAR the part's own build produces.
   */
  public static final String PART_DESCRIPTOR = "META-INF/vanillabp/%s-%s.properties";

  /**
   * The version of the platform integration this JAR belongs to.
   */
  public static final String PLATFORM_VERSION_KEY = "platform.version";

  /**
   * The oldest part the platform integration still serves.
   */
  public static final String OLDEST_PART_VERSION_KEY = "oldest-part.version";

  /**
   * The version of the part itself.
   */
  public static final String PART_VERSION_KEY = "part.version";

  /**
   * The Maven coordinates the part reports itself as.
   */
  public static final String PART_ARTIFACT_KEY = "part.artifact";

  private static final String UNKNOWN_VERSION = "unknown";

  /**
   * Parts nobody has to be told about again, so a setup having several adapter ids of one
   * type pays for the check once and says a warning once. A part which stops the boot is
   * not remembered: it has to reach every caller asking.
   */
  private static final Set<String> ALREADY_JUDGED = ConcurrentHashMap.newKeySet();

  private static volatile Properties platformDescriptor;

  private VanillaBpParts() {
    // static helper
  }

  /**
   * What the boot has to say about one part.
   *
   * @param stopsTheBoot Whether the application must not start
   * @param message The text naming both versions and the line to change
   */
  public record PartVerdict(boolean stopsTheBoot, String message) {
  }

  /**
   * Which platform integration this application runs. It is read from the descriptor on
   * the classpath, and every message about a part which does not fit names it.
   *
   * @return The version of the VanillaBP platform integration found on the classpath, or
   *         <code>unknown</code> if it cannot be determined
   */
  public static String platformVersion() {

    return Optional
        .ofNullable(platformDescriptor().getProperty(PLATFORM_VERSION_KEY))
        .orElse(UNKNOWN_VERSION);

  }

  /**
   * The oldest part this platform integration still serves. It is a single number, set
   * deliberately with every release, not a list of pairs: a list would have to name
   * versions which do not exist yet, and every release would have to touch it.
   *
   * @return The oldest version of a part this platform integration serves, or
   *         <code>null</code> if the platform names none
   */
  public static String oldestPartVersionServed() {

    return platformDescriptor().getProperty(OLDEST_PART_VERSION_KEY);

  }

  /**
   * Fails if the given BPMS adapter and the platform integration on the classpath do not
   * belong together, and warns if that cannot be told. An adapter calls this from the
   * constructor of its deployment service, because a platform integration older than this
   * check cannot contain the check and the adapter is then the only part able to report
   * the pair.
   * <p>
   * What is judged is the descriptor the adapter ships,
   * <code>META-INF/vanillabp/adapter-&lt;adapterType&gt;.properties</code> - see the type
   * javadoc for the three keys and for what is read when the file is missing or
   * incomplete. Without that file there is nothing to judge and this call warns once.
   *
   * @param adapterType The adapter's type (e.g. <code>camunda7</code>), which also names
   *        the descriptor
   * @param adapterClass A class of the adapter, used as the source of the class loader
   *        the descriptor is read from
   * @throws IllegalStateException If the two must not run together
   */
  public static void requireAdapterFitsPlatform(
      final String adapterType,
      final Class<?> adapterClass) {

    stopTheBootOrWarn(judge(PartKind.ADAPTER, adapterType, adapterClass));

  }

  /**
   * Fails if the given extension and the platform integration on the classpath do not
   * belong together, and warns if that cannot be told.
   *
   * @param extensionName The extension's name (e.g. <code>business-cockpit</code>)
   * @param extensionClass A class of the extension, used as the source of the class
   *        loader the descriptor is read from
   * @throws IllegalStateException If the two must not run together
   */
  public static void requireExtensionFitsPlatform(
      final String extensionName,
      final Class<?> extensionClass) {

    stopTheBootOrWarn(judge(PartKind.EXTENSION, extensionName, extensionClass));

  }

  /**
   * Judges one part against the platform integration on the classpath. A part which was
   * judged before without a finding is skipped.
   *
   * @param kind Whether the part is an adapter or an extension
   * @param name The name the part ships its version descriptor under
   * @param partClass A class of the part, used as the source of the class loader the
   *        descriptor is read from
   * @return What to say about the part, or empty if it fits and nothing needs saying
   */
  public static Optional<PartVerdict> judge(
      final PartKind kind,
      final String name,
      final Class<?> partClass) {

    if (ALREADY_JUDGED.contains(key(kind, name))) {
      return Optional.empty();
    }

    final var verdict = judge(kind, name, readDescriptor(partClass, descriptorOf(kind, name)));
    // a warning is said once, a part which stops the boot is reported to every caller
    if (verdict.map(found -> !found.stopsTheBoot()).orElse(Boolean.TRUE)) {
      ALREADY_JUDGED.add(key(kind, name));
    }
    return verdict;

  }

  /**
   * Judges one part whose version descriptor was already read, which is what the Quarkus
   * extension does while building: it walks the archives of the application and finds the
   * descriptors there.
   *
   * @param kind Whether the part is an adapter or an extension
   * @param name The name the part ships its version descriptor under
   * @param descriptor The content of the part's version descriptor, or <code>null</code>
   *        if the part ships none
   * @return What to say about the part, or empty if it fits and nothing needs saying
   */
  public static Optional<PartVerdict> judge(
      final PartKind kind,
      final String name,
      final Properties descriptor) {

    final var foundPlatformVersion = platformVersion();
    if (descriptor == null) {
      return Optional.of(new PartVerdict(false, """
          The VanillaBP %s '%s' does not say which VanillaBP platform integration it was built \
          against, so this boot cannot tell whether the two belong together. The platform \
          integration found is %s. If this application later fails with a NoSuchMethodError or a \
          NoClassDefFoundError, this pair is the first thing to look at.
          A part says it by shipping '%s' holding the keys '%s', '%s' and '%s'."""
          .formatted(
              kind.word(),
              name,
              foundPlatformVersion,
              descriptorOf(kind, name),
              PART_VERSION_KEY,
              PART_ARTIFACT_KEY,
              PLATFORM_VERSION_KEY)));
    }

    final var partVersion = value(descriptor, PART_VERSION_KEY);
    final var partArtifact = value(descriptor, PART_ARTIFACT_KEY);
    final var requiredPlatformVersion = value(descriptor, PLATFORM_VERSION_KEY);
    final var oldestPartVersion = oldestPartVersionServed();

    if ((requiredPlatformVersion != null) && comparable(foundPlatformVersion,
        requiredPlatformVersion) && !isAtLeast(foundPlatformVersion, requiredPlatformVersion)) {
      return Optional.of(new PartVerdict(true, """
          The VanillaBP %s '%s' %s was built against the VanillaBP platform integration %s, but \
          %s is on the classpath. These two were never built and never tested together, so this \
          application does not start.
          Neither Maven nor Gradle reports this as a conflict: a version your application manages \
          wins over the version a dependency asks for, even if that means a downgrade.
          Raise the platform integration to %s or newer. That is the dependency \
          'io.vanillabp:vanillabp-spring-boot-integration' respectively \
          'io.vanillabp:vanillabp-quarkus-integration', or the import of 'io.vanillabp:vanillabp-bom' \
          if you pin the VanillaBP versions that way. The other way round also works: use a version \
          of %s built against %s or older."""
          .formatted(
              kind.word(),
              name,
              partVersion == null ? UNKNOWN_VERSION : partVersion,
              requiredPlatformVersion,
              foundPlatformVersion,
              requiredPlatformVersion,
              partArtifact == null ? "the "
                  + kind.word()
                  : "'"
                      + partArtifact
                      + "'",
              foundPlatformVersion)));
    }

    if ((partVersion != null) && (oldestPartVersion != null) && comparable(partVersion,
        oldestPartVersion) && !isAtLeast(partVersion, oldestPartVersion)) {
      return Optional.of(new PartVerdict(true, """
          The VanillaBP %s '%s' %s is older than the oldest one the VanillaBP platform integration \
          %s still serves, which is %s. These two were never built and never tested together, so \
          this application does not start.
          Raise %s and every other artifact of that %s to %s or newer. The other way round also \
          works: use the platform integration this %s was built against, which is %s."""
          .formatted(
              kind.word(),
              name,
              partVersion,
              foundPlatformVersion,
              oldestPartVersion,
              partArtifact == null ? "the "
                  + kind.word()
                  : "'"
                      + partArtifact
                      + "'",
              kind.word(),
              oldestPartVersion,
              kind.word(),
              requiredPlatformVersion == null ? UNKNOWN_VERSION : requiredPlatformVersion)));
    }

    if ((partVersion == null) || (requiredPlatformVersion == null)) {
      return Optional.of(new PartVerdict(false, """
          The version descriptor '%s' of the VanillaBP %s '%s' is incomplete, so this boot cannot \
          tell whether the %s and the platform integration %s belong together. The keys '%s' and \
          '%s' are the ones which matter."""
          .formatted(
              descriptorOf(kind, name),
              kind.word(),
              name,
              kind.word(),
              foundPlatformVersion,
              PART_VERSION_KEY,
              PLATFORM_VERSION_KEY)));
    }

    if (!comparable(foundPlatformVersion,
        requiredPlatformVersion) || ((oldestPartVersion != null) && !comparable(partVersion, oldestPartVersion))) {
      return Optional.of(new PartVerdict(false, """
          The VanillaBP %s '%s' %s was built against the VanillaBP platform integration %s and \
          %s is on the classpath. One of these versions is not a version this check can compare, \
          so it cannot tell whether the two belong together. A build of your own is a good reason \
          for that, and then this warning is all which happens."""
          .formatted(
              kind.word(),
              name,
              partVersion,
              requiredPlatformVersion,
              foundPlatformVersion)));
    }

    log.debug("The VanillaBP {} '{}' {} fits the platform integration {}", kind.word(), name, partVersion,
        foundPlatformVersion);
    return Optional.empty();

  }

  /**
   * Where a part's version descriptor lies on the classpath. A part which wants to be
   * judged puts its file there; a part which ships none is only warned about (see
   * decision 71 in the repository's DECISIONS.md).
   *
   * @param kind Whether the part is an adapter or an extension
   * @param name The name the part ships its version descriptor under
   * @return The path of the part's version descriptor
   */
  public static String descriptorOf(
      final PartKind kind,
      final String name) {

    return PART_DESCRIPTOR.formatted(kind.word(), name);

  }

  /**
   * Compares two versions by their numeric parts, ignoring the qualifier, so
   * <code>2.0.0-SNAPSHOT</code> counts as <code>2.0.0</code> and a development build
   * works.
   *
   * @param actual The version found
   * @param required The version required
   * @return Whether <code>actual</code> is the same as or newer than <code>required</code>
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
   * @param first One version
   * @param second Another version
   * @return Whether both are versions this check understands
   */
  static boolean comparable(
      final String first,
      final String second) {

    return (numericParts(first) != null) && (numericParts(second) != null);

  }

  /**
   * Reports a verdict the way the caller asked for it: a part which must not run stops
   * the boot, a part nobody can judge is worth a warning.
   *
   * @param verdict What the check found
   */
  private static void stopTheBootOrWarn(
      final Optional<PartVerdict> verdict) {

    verdict.ifPresent(found -> {
      if (found.stopsTheBoot()) {
        throw new IllegalStateException(found.message());
      }
      log.warn(found.message());
    });

  }

  /**
   * @param version A version like <code>2.1.0-SNAPSHOT</code>
   * @return The numeric parts of the version, or <code>null</code> if it cannot be read
   */
  private static int[] numericParts(
      final String version) {

    if ((version == null) || version.isBlank()) {
      return null;
    }

    final var qualifierAt = version.indexOf('-');
    final var numeric = qualifierAt == -1 ? version : version.substring(0, qualifierAt);
    final var parts = numeric.split("\\.");
    final var numbers = new int[parts.length];
    for (int i = 0; i < parts.length; i++) {
      try {
        numbers[i] = Integer.parseInt(parts[i]);
      } catch (final NumberFormatException e) {
        return null;
      }
    }
    return numbers;

  }

  private static Properties platformDescriptor() {

    var descriptor = platformDescriptor;
    if (descriptor == null) {
      descriptor = readDescriptor(VanillaBpParts.class, PLATFORM_DESCRIPTOR);
      platformDescriptor = descriptor == null ? new Properties() : descriptor;
      descriptor = platformDescriptor;
    }
    return descriptor;

  }

  private static String key(
      final PartKind kind,
      final String name) {

    return kind.word() + ' ' + name;

  }

  private static String value(
      final Properties descriptor,
      final String key) {

    final var found = descriptor.getProperty(key);
    return (found == null) || found.isBlank() ? null : found.trim();

  }

  private static Properties readDescriptor(
      final Class<?> owner,
      final String path) {

    try (InputStream in = owner.getResourceAsStream('/' + path)) {
      if (in == null) {
        return null;
      }
      final var descriptor = new Properties();
      descriptor.load(in);
      return descriptor;
    } catch (final IOException e) {
      log.debug("Could not read '{}'", path, e);
      return null;
    }

  }

}
