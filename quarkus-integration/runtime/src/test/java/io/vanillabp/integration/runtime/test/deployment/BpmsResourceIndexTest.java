package io.vanillabp.integration.runtime.test.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.runtime.deployment.BpmsResourceIndex;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Unit test of the build-time BPMN index's runtime lookup: location normalization
 * (Spring-style <code>classpath:</code> prefixes, slashes) and loading of indexed
 * resources with subdirectory-preserving relative keys.
 */
@ExtendWith(SuppressOutputExtension.class)
public class BpmsResourceIndexTest {

  @Test
  @DisplayName("Locations are normalized: classpath prefixes and slashes")
  public void normalization() {

    assertEquals("processes/dummy/", BpmsResourceIndex.normalize("classpath:processes/dummy"));
    assertEquals("processes/dummy/", BpmsResourceIndex.normalize("classpath*:/processes/dummy/"));
    assertEquals("processes/dummy/", BpmsResourceIndex.normalize("/processes/dummy"));
    assertEquals("processes/dummy/", BpmsResourceIndex.normalize("processes/dummy/"));
    assertEquals("", BpmsResourceIndex.normalize("classpath:/"));

  }

  @Test
  @DisplayName("Non-classpath locations are rejected with a guiding message")
  public void nonClasspathLocationRejected() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> BpmsResourceIndex.normalize("file:/opt/bpmn"));
    assertTrue(exception.getMessage().contains("file:/opt/bpmn"));
    assertTrue(exception.getMessage().contains("classpath:"));

  }

  @Test
  @DisplayName("Indexed resources are loaded below the location with subdirectory-preserving keys")
  public void indexedResourcesAreLoaded() throws Exception {

    // the test classpath resources below bpmn-index-test/ act as the "application
    // archive" content indexed at build time
    final var index = BpmsResourceIndex
        .builder()
        .workflowModuleIds(List.of("test-module"))
        .resourcePaths(List.of(
            "bpmn-index-test/processes/first.bpmn",
            "bpmn-index-test/processes/sub/second.bpmn",
            "bpmn-index-test/other-location/third.bpmn"))
        .build();

    final var resources = index.loadResources("classpath:bpmn-index-test/processes", ".bpmn");
    try {
      assertEquals(List.of("first.bpmn", "sub/second.bpmn"), List.copyOf(resources.keySet()));
    } finally {
      for (final var stream : resources.values()) {
        stream.close();
      }
    }

  }

  @Test
  @DisplayName("The extension asked for decides which of the indexed files are handed out")
  public void theExtensionDecidesWhatIsHandedOut() throws Exception {

    final var index = BpmsResourceIndex
        .builder()
        .workflowModuleIds(List.of("test-module"))
        .resourcePaths(List.of(
            "bpmn-index-test/processes/first.bpmn",
            "bpmn-index-test/processes/rating.dmn"))
        .build();

    final var decisions = index.loadResources("classpath:bpmn-index-test/processes", ".dmn");
    try {
      assertEquals(List.of("rating.dmn"), List.copyOf(decisions.keySet()));
    } finally {
      for (final var stream : decisions.values()) {
        stream.close();
      }
    }

  }

  @Test
  @DisplayName("An indexed resource missing at runtime yields a guiding failure")
  public void missingResourceFails() {

    final var index = BpmsResourceIndex
        .builder()
        .workflowModuleIds(List.of("test-module"))
        .resourcePaths(List.of("bpmn-index-test/processes/not-there.bpmn"))
        .build();

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> index.loadResources("bpmn-index-test/processes", ".bpmn"));
    assertTrue(exception.getMessage().contains("not-there.bpmn"));

  }

}
