package io.vanillabp.integration.utils;

import java.util.LinkedList;
import java.util.List;
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
 * A classpath scanner for classes matching metadata predicates (e.g. classes
 * carrying a certain annotation).
 */
@Slf4j
public class ClasspathScanner {

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
   * Determine all classes matching the given predicates.
   *
   * @param basePackage The base-package to restrict the search
   * @param filters The predicates
   * @return All matching classes
   * @throws Exception Thrown if accessing classes fails
   */
  @SafeVarargs
  public final List<Class<?>> allClasses(
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
  public final List<Class<?>> allClasses(
      final ResourceLoader resourceLoader,
      final String basePackage,
      final Predicate<MetadataReader>... filters) throws Exception {

    final var packageSearchPath = "%s%s/**/*.class".formatted(
        ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX,
        ClassUtils.convertClassNameToResourcePath(SystemPropertyUtils.resolvePlaceholders(basePackage)));

    final List<Class<?>> classes = new LinkedList<>();

    final var resourcePatternResolver = getResourcePatternResolver(resourceLoader);
    final var resources = resourcePatternResolver.getResources(packageSearchPath);

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
