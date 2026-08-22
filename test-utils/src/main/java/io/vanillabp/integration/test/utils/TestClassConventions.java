package io.vanillabp.integration.test.utils;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The convention a build can check by itself: every test class registers
 * {@link SuppressOutputExtension}, so a build log carries what a FAILING test printed
 * and nothing else.
 * <p>
 * Story 108 is why this is a check and not a sentence: the rule stood in the testing
 * conventions and in a checklist, and 13 of 373 test classes across two repositories
 * still did not follow it. All 13 were quiet at the time, which is why nobody noticed.
 * A class starts printing the day it gets a mock which warns, and then the noise
 * arrives without a test having changed.
 * <p>
 * The check reads sources instead of compiled classes, so ONE test per repository
 * covers every module of it, the per-release-line test sources of the Camunda 8 adapter
 * (<code>src/test/java-line-8.9</code>) included.
 */
public final class TestClassConventions {

  /**
   * What makes a source file a test class. A file declaring none of these has nothing to
   * suppress: it is a helper, a test double, or a base class whose subclasses carry the
   * tests and, with them, the annotation.
   */
  private static final Pattern TEST_METHOD = Pattern
      .compile("^\\s*@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b",
          Pattern.MULTILINE);

  private static final String SUPPRESSION = SuppressOutputExtension.class.getSimpleName();

  private TestClassConventions() {
  }

  /**
   * The test classes of a repository which do not register
   * {@link SuppressOutputExtension}.
   *
   * @param repositoryRoot The repository's root directory
   * @return The offending source files, relative to the root, in a stable order
   */
  public static List<Path> testClassesWithoutOutputSuppression(
      final Path repositoryRoot) {

    try (var files = Files.walk(repositoryRoot)) {
      return files
          .filter(TestClassConventions::isTestSourceFile)
          .filter(file -> lacksSuppression(read(file)))
          .map(repositoryRoot::relativize)
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Could not read the test sources below '%s'".formatted(repositoryRoot), e);
    }

  }

  /**
   * The message of a failing check: which classes, and what to do about them.
   *
   * @param offenders What {@link #testClassesWithoutOutputSuppression(Path)} returned
   * @return A message naming every offending class
   */
  public static String describeTestClassesWithoutOutputSuppression(
      final Collection<Path> offenders) {

    return """
        %d test class(es) do not register %s, so whatever they print reaches the log of a \
        GREEN build:
        %s
        Add '@ExtendWith(%s.class)' to each of them. A class whose output arrives after the \
        class has finished (Testcontainers, a database driver) adds \
        '@%s.SuppressBackgroundOutput' as well."""
        .formatted(
            offenders.size(),
            SUPPRESSION,
            offenders
                .stream()
                .map(offender -> "  "
                    + offender)
                .collect(Collectors.joining("\n")),
            SUPPRESSION,
            SUPPRESSION);

  }

  private static boolean isTestSourceFile(
      final Path file) {

    final var path = file.toString().replace('\\', '/');
    if (!path.endsWith(".java") || path.contains("/target/")) {
      // a generated copy below 'target' is not a source, and the check must not judge it
      return false;
    }
    // 'src/test/java' also matches the per-line test sources 'src/test/java-line-8.9'
    return path.contains("/src/test/java") && TEST_METHOD.matcher(read(file)).find();

  }

  private static boolean lacksSuppression(
      final String source) {

    return !source.contains(SUPPRESSION);

  }

  private static String read(
      final Path file) {

    try {
      return Files.readString(file, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("Could not read '%s'".formatted(file), e);
    }

  }

}
