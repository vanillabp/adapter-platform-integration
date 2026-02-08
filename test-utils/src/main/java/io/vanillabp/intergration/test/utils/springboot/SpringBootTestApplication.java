package io.vanillabp.intergration.test.utils.springboot;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.function.Predicate;

import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Utility for building Spring Boot test applications with custom classpath
 * resources. Allows adding and hiding resources without separate Maven modules.
 *
 * <p>Usage example:
 * <pre>{@code
 * try (var testApp = SpringBootTestApplication.builder()
 *     .addResource("META-INF/workflow-module", "test-module")
 *     .hideResource("META-INF/workflow-module")
 *     .build()) {
 *   testApp.applicationBuilder(TestApplication.class).run();
 * }
 * }</pre>
 *
 * <p><b>Limitation:</b> This utility isolates resources (e.g., META-INF files,
 * BPMN, YAML), not classes. For class isolation, separate Maven test modules
 * are still required.
 */
public class SpringBootTestApplication implements AutoCloseable {

  private final Path tempDir;
  private final ResourceFilteringClassLoader classLoader;

  private SpringBootTestApplication(
      final Path tempDir,
      final ResourceFilteringClassLoader classLoader) {

    this.tempDir = tempDir;
    this.classLoader = classLoader;
  }

  public static Builder builder() {
    return new Builder();
  }

  public ClassLoader getClassLoader() {
    return classLoader;
  }

  /**
   * Returns an {@link ApplicationContextRunner} pre-configured with the
   * custom ClassLoader.
   */
  public ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner()
        .withClassLoader(classLoader);
  }

  /**
   * Returns a {@link SpringApplicationBuilder} pre-configured with a
   * ResourceLoader that uses the custom ClassLoader.
   */
  public SpringApplicationBuilder applicationBuilder(
      final Class<?>... sources) {

    return new SpringApplicationBuilder(sources)
        .resourceLoader(new DefaultResourceLoader(classLoader));
  }

  @Override
  public void close() throws IOException {
    classLoader.close();
    deleteRecursively(tempDir);
  }

  private static void deleteRecursively(
      final Path path) throws IOException {

    if (!Files.exists(path)) {
      return;
    }
    Files.walkFileTree(path, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult visitFile(
          final Path file,
          final BasicFileAttributes attrs) throws IOException {

        Files.delete(file);
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult postVisitDirectory(
          final Path dir,
          final IOException exc) throws IOException {

        Files.delete(dir);
        return FileVisitResult.CONTINUE;
      }
    });
  }

  /**
   * A ClassLoader that serves resources from a temp directory and can hide
   * resources from the parent ClassLoader.
   *
   * <p>Behavior:
   * <ul>
   *   <li>Hidden resources are only resolved from the temp directory URLs</li>
   *   <li>Non-hidden resources are resolved normally (parent + temp dir)</li>
   * </ul>
   */
  static class ResourceFilteringClassLoader extends URLClassLoader {

    private final List<Predicate<String>> hidePredicates;

    ResourceFilteringClassLoader(
        final URL[] urls,
        final ClassLoader parent,
        final List<Predicate<String>> hidePredicates) {

      super(urls, parent);
      this.hidePredicates = hidePredicates;
    }

    private boolean isHidden(
        final String name) {
      for (final var predicate : hidePredicates) {
        if (predicate.test(name)) {
          return true;
        }
      }
      return false;
    }

    @Override
    public URL getResource(
        final String name) {
      if (isHidden(name)) {
        return findResource(name);
      }
      return super.getResource(name);
    }

    @Override
    public Enumeration<URL> getResources(
        final String name) throws IOException {

      if (isHidden(name)) {
        return findResources(name);
      }
      return super.getResources(name);
    }

    @Override
    public InputStream getResourceAsStream(
        final String name) {
      final var url = getResource(name);
      if (url == null) {
        return null;
      }
      try {
        return url.openStream();
      } catch (IOException e) {
        return null;
      }
    }
  }

  public static class Builder {

    private final List<ResourceEntry> resources = new ArrayList<>();
    private final List<Predicate<String>> hidePredicates = new ArrayList<>();

    Builder() {
    }

    /**
     * Add a resource with the given string content.
     */
    public Builder addResource(
        final String path,
        final String content) {

      resources.add(new ResourceEntry(
          path, content.getBytes(StandardCharsets.UTF_8)));
      return this;
    }

    /**
     * Add a resource with the given byte content.
     */
    public Builder addResource(
        final String path,
        final byte[] content) {

      resources.add(new ResourceEntry(path, content));
      return this;
    }

    /**
     * Copy a resource from the current classpath to the temp directory.
     *
     * @throws IllegalArgumentException if the resource is not found on the
     *     classpath
     */
    public Builder addResource(
        final String path) {
      resources.add(new ResourceEntry(path, null));
      return this;
    }

    /**
     * Hide resources whose name contains the given fragment. Hidden
     * resources will only be resolved from the temp directory.
     */
    public Builder hideResource(
        final String nameFragment) {
      hidePredicates.add(name -> name.contains(nameFragment));
      return this;
    }

    public SpringBootTestApplication build() throws IOException {
      final var tempDir = Files
          .createTempDirectory("vanillabp-test-");

      for (final var entry : resources) {
        final var resourcePath = tempDir.resolve(entry.path);
        Files.createDirectories(resourcePath.getParent());

        if (entry.content != null) {
          Files.copy(
              new ByteArrayInputStream(entry.content),
              resourcePath);
        } else {
          final var source = Thread
              .currentThread()
              .getContextClassLoader()
              .getResourceAsStream(entry.path);
          if (source == null) {
            throw new IllegalArgumentException(
                "Resource not found on classpath: "
                    + entry.path);
          }
          try (source) {
            Files.copy(source, resourcePath);
          }
        }
      }

      final var urls = new URL[]{
          tempDir.toUri().toURL()
      };
      final var parent = Thread
          .currentThread()
          .getContextClassLoader();
      final var classLoader = new ResourceFilteringClassLoader(
          urls, parent, Collections.unmodifiableList(hidePredicates));

      return new SpringBootTestApplication(tempDir, classLoader);
    }

    private record ResourceEntry(String path, byte[] content) {
    }
  }
}
