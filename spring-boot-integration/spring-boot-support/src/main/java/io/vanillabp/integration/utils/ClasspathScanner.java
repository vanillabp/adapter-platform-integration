package io.vanillabp.integration.utils;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.util.ClassUtils;
import org.springframework.util.SystemPropertyUtils;

import lombok.extern.slf4j.Slf4j;

/**
 * A classpath scanner
 */
@Slf4j
public class ClasspathScanner {

  /**
   * Cache to accelerate booting the application
   */
  private static final Map<String, Resource[]> cache = new HashMap<>();

  /**
   * Hide public constructor. Use static methods provided instead.
   */
  private ClasspathScanner() {
    // static class: hide public constructor
  }

  /**
   * @param resourceLoader Spring Boot resource loader
   * @return A {@link ResourcePatternResolver}
   */
  private static ResourcePatternResolver getResourcePatternResolver(
      final ResourceLoader resourceLoader) {
    if (resourceLoader == null) {
      return new PathMatchingResourcePatternResolver();
    } else {
      return new PathMatchingResourcePatternResolver(resourceLoader);
    }
  }

  /**
   * Determine all resources matching the given predicates.
   *
   * @param filters The predicates
   * @return All matching resources
   * @throws Exception Thrown if accessing resources fails
   */
  @SafeVarargs
  @SuppressWarnings("unused")
  public static List<Resource> allResources(
      final Predicate<Resource>... filters) throws Exception {

    return allResources(null, null, filters);

  }

  /**
   * Determine all resources matching the given predicates.
   *
   * @param resourceLoader The resource loader
   * @param filters The predicates
   * @return All matching resources
   * @throws Exception Thrown if accessing resources fails
   */
  @SafeVarargs
  @SuppressWarnings("unused")
  public static List<Resource> allResources(
      final ResourceLoader resourceLoader,
      final Predicate<Resource>... filters) throws Exception {

    return allResources(resourceLoader, null, filters);

  }

  /**
   * Determine all resources matching the given predicates within the given base-path.
   *
   * @param basePath The base-path to restrict the search
   * @param filters The predicates
   * @return All matching resources
   * @throws Exception Thrown if accessing resources fails
   */
  @SafeVarargs
  @SuppressWarnings("unused")
  public static List<Resource> allResources(
      final String basePath,
      final Predicate<Resource>... filters) throws Exception {

    return allResources(null, basePath, filters);

  }

  /**
   * Determine all resources matching the given predicates within the given base-path.
   *
   * @param resourceLoader The resource loader
   * @param basePath The base-path to restrict the search
   * @param filters The predicates
   * @return All matching resources
   * @throws Exception Thrown if accessing resources fails
   */
  @SafeVarargs
  @SuppressWarnings("unused")
  public static List<Resource> allResources(
      final ResourceLoader resourceLoader,
      final String basePath,
      final Predicate<Resource>... filters) throws Exception {

    final var searchPath = "%s%s/**/*".formatted(
        ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX,
        basePath == null ? "" : basePath);

    final Resource[] resources;
    synchronized (cache) {
      final var cachedResources = cache.get(searchPath);
      if (cachedResources != null) {
        resources = cachedResources;
      } else {
        resources = getResourcePatternResolver(resourceLoader).getResources(searchPath);
        cache.put(searchPath, resources);
      }
    }

    final List<Resource> result = new LinkedList<>();

    for (final var resource : resources) {
      if (resource.isReadable()) {
        boolean complies = true;
        for (Predicate<Resource> filter : filters) {
          if (!filter.test(resource)) {
            complies = false;
            break;
          }
        }
        if (complies) {
          result.add(resource);
        }
      }
    }

    return result;

  }

  /**
   * Determine all classes matching the given predicates.
   *
   * @param basePackage The base-package to restrict the search
   * @param filters The predicates
   * @return All matching classes
   * @throws Exception Thrown if accessing classes fails
   */
  @SafeVarargs
  @SuppressWarnings("unused")
  public static List<Class<?>> allClasses(
      final String basePackage,
      final Predicate<MetadataReader>... filters) throws Exception {

    return allClasses(null, basePackage, filters);

  }

  /**
   * Determine all classes matching the given predicates within the given base-package.
   *
   * @param resourceLoader The resource loader
   * @param basePackage The base-package to restrict the search
   * @param filters The predicates
   * @return All matching classes
   * @throws Exception Thrown if accessing classes fails
   */
  @SafeVarargs
  @SuppressWarnings("unused")
  public static List<Class<?>> allClasses(
      final ResourceLoader resourceLoader,
      final String basePackage,
      final Predicate<MetadataReader>... filters) throws Exception {

    final var packageSearchPath = "%s%s/**/*.class".formatted(
        ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX,
        ClassUtils.convertClassNameToResourcePath(SystemPropertyUtils.resolvePlaceholders(basePackage)));

    final List<Class<?>> classes = new LinkedList<>();

    final var resourcePatternResolver = getResourcePatternResolver(resourceLoader);

    final Resource[] resources;
    synchronized (cache) {
      final var cachedResources = cache.get(packageSearchPath);
      if (cachedResources != null) {
        resources = cachedResources;
      } else {
        resources = resourcePatternResolver.getResources(packageSearchPath);
        cache.put(packageSearchPath, resources);
      }
    }

    final var metadataReaderFactory = new CachingMetadataReaderFactory(resourcePatternResolver);
    for (Resource resource : resources) {
      if (resource.isReadable()) {
        try {
          final var metadataReader = metadataReaderFactory.getMetadataReader(resource);
          boolean complies = true;
          for (Predicate<MetadataReader> filter : filters) {
            if (!filter.test(metadataReader)) {
              complies = false;
              break;
            }
          }
          if (complies) {
            try {
              final var c = Class.forName(metadataReader.getClassMetadata().getClassName());
              classes.add(c);
            } catch (Throwable e) {
              log.trace("Class not found: {}", metadataReader.getClassMetadata().getClassName());
            }
          }
        } catch (NoClassDefFoundError e) {
          log.debug("NoClassDefFoundError: it might be an optional dependency", e);
        }
      }
    }

    return classes;

  }


}
