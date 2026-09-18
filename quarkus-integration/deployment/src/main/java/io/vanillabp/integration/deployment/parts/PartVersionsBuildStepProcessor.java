package io.vanillabp.integration.deployment.parts;

import java.io.IOException;
import java.nio.file.Files;
import java.util.LinkedList;
import java.util.Optional;
import java.util.Properties;
import java.util.TreeMap;

import org.jboss.logging.Logger;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.nativeimage.NativeImageResourceBuildItem;
import io.quarkus.paths.PathVisit;
import io.vanillabp.integration.spi.parts.PartKind;
import io.vanillabp.integration.spi.parts.VanillaBpParts;

/**
 * Checks while building whether the BPMS adapters and the extensions of this application
 * belong to the VanillaBP platform integration they are built with, and puts their
 * version descriptors into the native image.
 * <p>
 * Quarkus knows the whole application while it builds it, so this is the earliest moment
 * the question can be asked, and a developer learns about a pair which does not belong
 * together before the application is even packaged. The same check runs again at startup,
 * for the adapters and extensions a build cannot see, and because a Spring Boot
 * application has no build step to ask.
 * <p>
 * Registering the descriptors matters as much as the check: a native image carries only
 * the resources it was told about, and a descriptor left out of the image turns every
 * pair into an unknown one.
 *
 * @see VanillaBpParts
 */
public class PartVersionsBuildStepProcessor {

  private static final Logger log = Logger.getLogger(PartVersionsBuildStepProcessor.class);

  private static final String DESCRIPTOR_FOLDER = "META-INF/vanillabp/";

  private static final String DESCRIPTOR_SUFFIX = ".properties";

  /**
   * @param applicationArchives The archives of this Quarkus build
   * @param nativeImageResources Producer putting the descriptors into the native image
   * @return The word that the parts were judged, which the deployment pipeline waits for
   */
  @BuildStep
  PartsCheckedBuildItem checkPartsBelongTogether(
      final ApplicationArchivesBuildItem applicationArchives,
      final BuildProducer<NativeImageResourceBuildItem> nativeImageResources) {

    // sorted + deduplicated: the same descriptor may show up in more than one archive
    final var descriptors = new TreeMap<String, Properties>();
    applicationArchives
        .getAllArchives()
        .forEach(archive -> archive
            .accept(openPathTree -> openPathTree
                .walk(visit -> Optional
                    .ofNullable(visit.getRelativePath("/"))
                    .filter(PartVersionsBuildStepProcessor::isVersionDescriptor)
                    .ifPresent(path -> {
                      nativeImageResources.produce(new NativeImageResourceBuildItem(path));
                      read(visit).ifPresent(descriptor -> descriptors.put(path, descriptor));
                    }))));

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
   * @param path The relative path of a file found in an application archive
   * @return Whether the file is a VanillaBP version descriptor
   */
  private static boolean isVersionDescriptor(
      final String path) {

    return path.startsWith(DESCRIPTOR_FOLDER) && path.endsWith(DESCRIPTOR_SUFFIX);

  }

  /**
   * Judges the part the given descriptor belongs to. The platform integration's own
   * descriptor is not a part and is only carried into the native image.
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
