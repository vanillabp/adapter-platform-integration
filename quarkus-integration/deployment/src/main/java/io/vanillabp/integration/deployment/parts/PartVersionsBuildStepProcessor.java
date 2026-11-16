package io.vanillabp.integration.deployment.parts;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedList;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

import org.jboss.logging.Logger;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.paths.PathTree;
import io.quarkus.paths.PathVisit;
import io.vanillabp.integration.spi.parts.PartKind;
import io.vanillabp.integration.spi.parts.VanillaBpParts;

/**
 * Checks during the build whether the BPMS adapters and the extensions of this application
 * belong to the VanillaBP platform integration they are built with. It also puts their
 * version descriptors into the native image.
 * <p>
 * Quarkus knows the whole application while it builds it. So this is the earliest moment to
 * ask the question. A developer learns about a pair which does not belong together before
 * the application is even packaged. The same check runs again at startup. It covers the
 * adapters and extensions a build cannot see, and a Spring Boot application has no build
 * step to ask.
 * <p>
 * Registering the descriptors matters as much as the check. A native image only carries the
 * resources it was told about. A descriptor left out of the image turns every pair into an
 * unknown one.
 * <p>
 * The descriptors are searched in the application and in every JAR of its runtime classpath.
 * The archives which Quarkus indexes are not enough. A BPMS adapter is usually an ordinary
 * JAR, and so is the JAR holding the platform's own descriptor. Without their descriptors, a
 * native binary warns that the adapter does not say which platform integration it was built
 * against. Each descriptor found is judged and registered, so the image carries exactly the
 * files the build has checked (see <code>VersionDescriptorsInTheNativeImageTest</code>).
 *
 * @see VanillaBpParts
 */
public class PartVersionsBuildStepProcessor {

  private static final Logger log = Logger.getLogger(PartVersionsBuildStepProcessor.class);

  private static final String DESCRIPTOR_DIRECTORY = "META-INF/vanillabp";

  private static final String DESCRIPTOR_FOLDER = DESCRIPTOR_DIRECTORY + '/';

  private static final String DESCRIPTOR_SUFFIX = ".properties";

  /**
   * Quarkus builds this processor while it augments the application and calls the build step
   * below on it. Nothing else builds it, and the step keeps no state in it.
   */
  public PartVersionsBuildStepProcessor() {
  }

  /**
   * @param applicationArchives The archives of this Quarkus build, used for the application
   *        itself
   * @param curateOutcome The model of this application, used for the JARs of its runtime
   *        classpath, whether Quarkus indexes them or not
   * @param nativeImageResources Producer putting the descriptors into the native image
   * @return The sign that the parts were checked. The deployment pipeline waits for it.
   */
  @BuildStep
  PartsCheckedBuildItem checkPartsBelongTogether(
      final ApplicationArchivesBuildItem applicationArchives,
      final CurateOutcomeBuildItem curateOutcome,
      final BuildProducer<NativeImageResourceBuildItem> nativeImageResources) {

    // sorted + deduplicated: the same descriptor may show up in more than one place. The
    // application comes first and wins, as it does on the classpath at runtime.
    final var descriptors = new TreeMap<String, Properties>();
    applicationArchives
        .getRootArchive()
        .accept(application -> collectDescriptors(application, descriptors));
    curateOutcome
        .getApplicationModel()
        .getRuntimeDependencies()
        .forEach(dependency -> collectDescriptors(dependency.getContentTree(), descriptors));

    if (!descriptors.isEmpty()) {
      nativeImageResources.produce(new NativeImageResourceBuildItem(descriptors
          .keySet()
          .stream()
          .toList()));
    }

    final var reasonsNotToBuild = new LinkedList<String>();
    descriptors
        .forEach((
            path,
            descriptor) -> judge(path, descriptor)
                .ifPresent(verdict -> {
                  if (verdict.stopsTheBoot()) {
                    reasonsNotToBuild.add(verdict.message());
                  } else {
                    log.warn(verdict.message());
                  }
                }));

    if (!reasonsNotToBuild.isEmpty()) {
      throw new IllegalStateException(String.join(System.lineSeparator() + System.lineSeparator(),
          reasonsNotToBuild));
    }

    return new PartsCheckedBuildItem();

  }

  /**
   * Reads the descriptors of one archive. A descriptor already read from an earlier archive
   * is kept.
   *
   * @param archive The content of the application or of one JAR of its classpath
   * @param descriptors The descriptors read so far, by their relative path
   */
  private static void collectDescriptors(
      final PathTree archive,
      final Map<String, Properties> descriptors) {

    archive.walkIfContains(DESCRIPTOR_DIRECTORY, visit -> Optional
        .ofNullable(visit.getRelativePath("/"))
        .filter(PartVersionsBuildStepProcessor::isVersionDescriptor)
        .filter(path -> !descriptors.containsKey(path))
        .ifPresent(path -> read(visit).ifPresent(descriptor -> descriptors.put(path, descriptor))));

  }

  /**
   * @param path The relative path of a file found in an archive
   * @return Whether the file is a VanillaBP version descriptor
   */
  private static boolean isVersionDescriptor(
      final String path) {

    return path.startsWith(DESCRIPTOR_FOLDER) && path.endsWith(DESCRIPTOR_SUFFIX);

  }

  /**
   * Checks the part which the given descriptor belongs to. The platform integration's own
   * descriptor is not a part. It is only carried into the native image.
   *
   * @param path The relative path of the descriptor
   * @param descriptor The content of the descriptor
   * @return What to say about the part, or empty if there is nothing to say
   */
  private static Optional<VanillaBpParts.PartVerdict> judge(
      final String path,
      final Properties descriptor) {

    final var filename = path.substring(DESCRIPTOR_FOLDER.length(), path.length() - DESCRIPTOR_SUFFIX.length());
    for (final var kind : PartKind.values()) {
      final var prefix = kind.word() + '-';
      if (filename.startsWith(prefix)) {
        return VanillaBpParts.judge(kind, filename.substring(prefix.length()), descriptor);
      }
    }
    return Optional.empty();

  }

  /**
   * @param visit The file found while walking an archive
   * @return The content of the file, or empty if it cannot be read
   */
  private static Optional<Properties> read(
      final PathVisit visit) {

    try (var in = Files.newInputStream(visit.getPath())) {
      final var result = new Properties();
      result.load(in);
      return Optional.of(result);
    } catch (final IOException e) {
      log.debugf(e, "Could not read '%s'", visit.getRelativePath("/"));
      return Optional.empty();
    }

  }

}
