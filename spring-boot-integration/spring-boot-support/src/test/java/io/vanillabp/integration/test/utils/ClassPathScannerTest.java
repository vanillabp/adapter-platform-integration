package io.vanillabp.integration.test.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;

import org.junit.jupiter.api.Test;

import io.vanillabp.integration.utils.ClasspathScanner;

public class ClassPathScannerTest {

  @Test
  public void testAllResources() throws Exception {

    final var allResources = ClasspathScanner.allResources();
    assertNotNull(allResources);
    assertTrue(allResources.size() > 1);
    assertTrue(allResources.stream().anyMatch(resource -> Objects.equals(
        resource.getFilename(), "application.yaml")));

    final var applicationYaml = ClasspathScanner.allResources(resource -> Objects.equals(
        resource.getFilename(), "application.yaml"));
    assertNotNull(applicationYaml);
    assertEquals(1, applicationYaml.size());
    assertEquals("application.yaml", applicationYaml.getFirst().getFilename());

    final var classpathscanneryaml = ClasspathScanner.allResources("test");
    assertNotNull(classpathscanneryaml);
    assertEquals(1, classpathscanneryaml.size());
    assertEquals("classpathscanner.yaml", classpathscanneryaml.getFirst().getFilename());

    final var cached = ClasspathScanner.allResources("test");
    assertNotNull(cached);
    assertEquals(1, cached.size());
    assertEquals("classpathscanner.yaml", cached.getFirst().getFilename());
    assertEquals(classpathscanneryaml, cached);

  }

  @Test
  public void testAllClasses() throws Exception {

    final var now1 = System.currentTimeMillis();
    final var classpathScanner = ClasspathScanner.allClasses("io.vanillabp.integration.utils");
    assertNotNull(classpathScanner);
    assertTrue(classpathScanner.contains(ClasspathScanner.class));
    final var time1 = System.currentTimeMillis() - now1;

    final var now2 = System.currentTimeMillis();
    final var cached = ClasspathScanner.allClasses("io.vanillabp.integration.utils");
    assertNotNull(cached);
    assertTrue(classpathScanner.contains(ClasspathScanner.class));
    assertEquals(classpathScanner, cached);
    final var time2 = System.currentTimeMillis() - now2;
    assertTrue(time2 < time1);

    final var allClasses = ClasspathScanner.allClasses("",
        metadataReader -> metadataReader.getClassMetadata().getClassName().startsWith("io.vanillabp."));
    assertNotNull(allClasses);
    assertTrue(allClasses.size() > 1);
    assertTrue(allClasses.stream().anyMatch(clasz -> clasz.getSimpleName().equals("ClasspathScanner")));

  }

}
