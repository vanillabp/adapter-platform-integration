package io.vanillabp.integration.deployment.config;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.regex.Pattern;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.Produce;
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.pkg.builditem.ArtifactResultBuildItem;
import io.quarkus.deployment.pkg.steps.NativeOrNativeSourcesBuild;
import io.quarkus.runtime.configuration.ConfigUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * VanillaBP extension build step processor telling an application which of its own
 * profile-specific configuration files a native image is going to ignore.
 * <p>
 * Quarkus resolves the list of configuration files it reads while the image is built, so
 * <code>application-{profile}.yaml</code> reaches a binary only if that profile was active
 * during the build. A profile chosen when the binary starts switches the values VanillaBP and
 * the adapters read, and it switches the profile sections inside <code>application.yaml</code>,
 * but it adds no further file. On the JVM the same application reads the file, which is what
 * makes the difference easy to miss.
 * <p>
 * VanillaBP does not load those files itself. They belong to Quarkus, a second loading
 * mechanism next to it would have its own precedence and its own surprises, and each of the
 * ways out below is a one-line change in the application.
 */
@Slf4j
public class ProfileSpecificApplicationFilesBuildStepProcessor {

  /**
   * Matches <code>application-{profile}.{extension}</code> at the root of an archive, which is
   * where Quarkus reads the application's own configuration from.
   */
  private static final Pattern PROFILE_SPECIFIC_APPLICATION_FILE = Pattern
      .compile("application-([^/.]+)\\.(?:properties|yaml|yml)");

  /**
   * Profiles a packaged binary is not built to run: warning about their files would fire in
   * every project without naming a problem anybody has.
   */
  private static final Set<String> PROFILES_A_BINARY_IS_NOT_MEANT_FOR = Set.of("dev", "test");

  /**
   * Reports the application's profile-specific configuration files which the image being built
   * will not honor, and names the ways to reach the values in them.
   *
   * The step produces nothing, which is why it declares the result of the build as its
   * product: a step nobody waits for is dropped instead of being executed.
   *
   * @param applicationArchives The archives of this Quarkus build
   */
  @BuildStep(onlyIf = NativeOrNativeSourcesBuild.class)
  @Produce(ArtifactResultBuildItem.class)
  void warnAboutFilesTheImageWillNotHonor(
      final ApplicationArchivesBuildItem applicationArchives) {

    messageAboutFilesTheImageWillNotHonor(
        profileSpecificApplicationFiles(applicationArchives),
        ConfigUtils.getProfiles())
        .ifPresent(log::warn);

  }

  /**
   * Searches all application archives for files named <code>application-{profile}</code>.
   *
   * @param applicationArchives The archives of this Quarkus build
   * @return The names of those files, deduplicated because the same name may show up in more
   *         than one archive
   */
  private static SortedSet<String> profileSpecificApplicationFiles(
      final ApplicationArchivesBuildItem applicationArchives) {

    final var applicationFiles = new TreeSet<String>();
    applicationArchives
        .getAllArchives()
        .forEach(archive -> archive
            .accept(openPathTree -> openPathTree
                .walk(visit -> Optional
                    .ofNullable(visit.getRelativePath("/"))
                    .filter(relativePath -> PROFILE_SPECIFIC_APPLICATION_FILE
                        .matcher(relativePath)
                        .matches())
                    .ifPresent(applicationFiles::add))));
    return applicationFiles;

  }

  /**
   * Builds the warning for the files whose profile the image is not built with. A file of a
   * profile the build itself uses is part of the image and needs no warning, and neither do
   * the files of the profiles a packaged binary is not meant for.
   *
   * @param profileSpecificApplicationFiles The files found in the application's archives
   * @param profilesTheImageIsBuiltWith The profiles active while this image is being built
   * @return The warning, or nothing if every file found does reach the image
   */
  static Optional<String> messageAboutFilesTheImageWillNotHonor(
      final Collection<String> profileSpecificApplicationFiles,
      final Collection<String> profilesTheImageIsBuiltWith) {

    final var filesIgnored = profileSpecificApplicationFiles
        .stream()
        .filter(file -> profileOf(file)
            .filter(profile -> !profilesTheImageIsBuiltWith.contains(profile))
            .filter(profile -> !PROFILES_A_BINARY_IS_NOT_MEANT_FOR.contains(profile))
            .isPresent())
        .sorted()
        .toList();
    if (filesIgnored.isEmpty()) {
      return Optional.empty();
    }

    final var oneOfThem = filesIgnored.getFirst();
    final var itsProfile = profileOf(oneOfThem).orElseThrow();
    return Optional.of("""
        Configuration files of this application will not be honored by the native image \
        being built:
          %s
        Quarkus resolves its own configuration files while the image is built, and this one \
        is built with the profile(s) '%s', so a profile chosen when the binary starts adds \
        none of the files above. Pick the way to their values which fits your deployment:
          1. move the values into a profile section of 'application.yaml', '%%%s:', which is \
        part of every image and is chosen by '-Dquarkus.profile=%s' when the binary starts;
          2. build one image per profile, '-Dquarkus.profile=%s', which then needs no \
        argument at all when it starts;
          3. keep the file and embed it, 'quarkus.native.resources.includes=application-*.%s', \
        then name it when the binary starts, '-Dsmallrye.config.locations=%s'.
        Configuration files of workflow modules are not affected: VanillaBP puts them and \
        their profile-specific variants into the image, so a profile chosen at the binary \
        picks among them as it does on the JVM.""".formatted(
        String.join("\n  ", filesIgnored),
        String.join(",", profilesTheImageIsBuiltWith),
        itsProfile,
        itsProfile,
        itsProfile,
        extensionOf(oneOfThem),
        oneOfThem));

  }

  /**
   * @param applicationFile A file name matching {@link #PROFILE_SPECIFIC_APPLICATION_FILE}
   * @return The profile the file is named after
   */
  private static Optional<String> profileOf(
      final String applicationFile) {

    final var name = PROFILE_SPECIFIC_APPLICATION_FILE.matcher(applicationFile);
    return name.matches()
        ? Optional.of(name.group(1))
        : Optional.empty();

  }

  /**
   * @param applicationFile A file name matching {@link #PROFILE_SPECIFIC_APPLICATION_FILE}
   * @return The file extension, so the remedy suggested names the files the application has
   */
  private static String extensionOf(
      final String applicationFile) {

    return applicationFile.substring(applicationFile.lastIndexOf('.') + 1);

  }

}
