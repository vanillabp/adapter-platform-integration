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
 * This is a check and not a sentence because the rule stood in the testing
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

  /**
   * Annotations are matched at the start of a line, never anywhere in the file: a Javadoc
   * paragraph explaining {@code @Testcontainers} is prose, and reading it as a
   * registration reported a class which had done everything right.
   */
  private static final Pattern TESTCONTAINERS_ANNOTATION = Pattern
      .compile("^@Testcontainers\\b", Pattern.MULTILINE);

  private static final Pattern SUPPRESSION_ANNOTATION = Pattern
      .compile("^@ExtendWith\\("
          + SUPPRESSION
          + "\\b", Pattern.MULTILINE);

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

  /**
   * The test classes which let another extension start containers BEFORE
   * {@link SuppressOutputExtension} is registered.
   * <p>
   * JUnit registers declarative extensions in the order they are written, and the
   * Testcontainers extension starts what it manages in its own {@code beforeAll}. Written
   * after {@code @Testcontainers}, the extension arrives too late: the Docker client has
   * already logged through an appender holding the real stdout. Measured on the schema
   * module of adapter-platform-integration, that was 2208 debug lines in a green build.
   *
   * @param repositoryRoot The repository's root directory
   * @return The offending source files, relative to the root, in a stable order
   */
  public static List<Path> testClassesSuppressingTooLate(
      final Path repositoryRoot) {

    try (var files = Files.walk(repositoryRoot)) {
      return files
          .filter(TestClassConventions::isTestSourceFile)
          .filter(file -> suppressesTooLate(read(file)))
          .map(repositoryRoot::relativize)
          .sorted()
          .toList();
    } catch (IOException e) {
      throw new UncheckedIOException(
          "Could not read the test sources below '%s'".formatted(repositoryRoot), e);
    }

  }

  /**
   * The message of a failing order check: which classes, and what to do about them.
   *
   * @param offenders What {@link #testClassesSuppressingTooLate(Path)} returned
   * @return A message naming every offending class
   */
  public static String describeTestClassesSuppressingTooLate(
      final Collection<Path> offenders) {

    return """
        %d test class(es) register %s AFTER '@Testcontainers', so the container is started \
        before anything captures what the Docker client logs:
        %s
        Write '@ExtendWith(%s.class)' above '@Testcontainers'. What starts even earlier \
        than that - Docker detection from an ExecutionCondition, the Ryuk reaper, an image \
        pull - is reached by no extension at all and needs a 'logback-test.xml' holding \
        'org.testcontainers', 'tc' and 'com.github.dockerjava' at WARN."""
        .formatted(
            offenders.size(),
            SUPPRESSION,
            offenders
                .stream()
                .map(offender -> "  "
                    + offender)
                .collect(Collectors.joining("\n")),
            SUPPRESSION);

  }

  private static boolean suppressesTooLate(
      final String source) {

    if (lacksSuppression(source)) {
      // that is the other check's finding, and reporting it twice helps nobody
      return false;
    }
    final var containers = firstMatch(TESTCONTAINERS_ANNOTATION, source);
    if (containers < 0) {
      return false;
    }
    final var suppression = firstMatch(SUPPRESSION_ANNOTATION, source);
    return (suppression < 0) || (containers < suppression);

  }

  private static int firstMatch(
      final Pattern pattern,
      final String source) {

    final var matcher = pattern.matcher(source);
    return matcher.find() ? matcher.start() : -1;

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
