package io.vanillabp.integration.test.utils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.utils.ClasspathScanner;

@ExtendWith(SuppressOutputExtension.class)
public class ClassPathScannerTest {

  private final ClasspathScanner testee = new ClasspathScanner();

  @Test
  public void testAllClasses() throws Exception {

    final var classpathScanner = testee.allClasses("io.vanillabp.integration.utils");
    assertNotNull(classpathScanner);
    assertTrue(classpathScanner.contains(ClasspathScanner.class));

    final var allClasses = testee.allClasses("",
        metadataReader -> metadataReader.getClassMetadata().getClassName().startsWith("io.vanillabp."));
    assertNotNull(allClasses);
    assertTrue(allClasses.size() > 1);
    assertTrue(allClasses.stream().anyMatch(clasz -> clasz.getSimpleName().equals("ClasspathScanner")));

  }

}
