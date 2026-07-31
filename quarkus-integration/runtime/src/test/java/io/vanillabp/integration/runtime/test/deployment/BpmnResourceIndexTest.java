package io.vanillabp.integration.runtime.test.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.runtime.deployment.BpmnResourceIndex;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Unit test of the build-time BPMN index's runtime lookup: location normalization
 * (Spring-style <code>classpath:</code> prefixes, slashes) and loading of indexed
 * resources with subdirectory-preserving relative keys.
 */
@ExtendWith(SuppressOutputExtension.class)
public class BpmnResourceIndexTest {

  @Test
  @DisplayName("Locations are normalized: classpath prefixes and slashes")
  public void normalization() {

    assertEquals("processes/dummy/", BpmnResourceIndex.normalize("classpath:processes/dummy"));
    assertEquals("processes/dummy/", BpmnResourceIndex.normalize("classpath*:/processes/dummy/"));
    assertEquals("processes/dummy/", BpmnResourceIndex.normalize("/processes/dummy"));
    assertEquals("processes/dummy/", BpmnResourceIndex.normalize("processes/dummy/"));
    assertEquals("", BpmnResourceIndex.normalize("classpath:/"));

  }

  @Test
  @DisplayName("Non-classpath locations are rejected with a guiding message")
  public void nonClasspathLocationRejected() {

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> BpmnResourceIndex.normalize("file:/opt/bpmn"));
    assertTrue(exception.getMessage().contains("file:/opt/bpmn"));
    assertTrue(exception.getMessage().contains("classpath:"));

  }

  @Test
  @DisplayName("Indexed resources are loaded below the location with subdirectory-preserving keys")
  public void loadBpmnResources() throws Exception {

    // the test classpath resources below bpmn-index-test/ act as the "application
    // archive" content indexed at build time
    final var index = BpmnResourceIndex
        .builder()
        .workflowModuleIds(List.of("test-module"))
        .bpmnResourcePaths(List.of(
            "bpmn-index-test/processes/first.bpmn",
            "bpmn-index-test/processes/sub/second.bpmn",
            "bpmn-index-test/other-location/third.bpmn"))
        .build();

    final var resources = index.loadBpmnResources("classpath:bpmn-index-test/processes");
    try {
      assertEquals(List.of("first.bpmn", "sub/second.bpmn"), List.copyOf(resources.keySet()));
    } finally {
      for (final var stream : resources.values()) {
        stream.close();
      }
    }

  }

  @Test
  @DisplayName("An indexed resource missing at runtime yields a guiding failure")
  public void missingResourceFails() {

    final var index = BpmnResourceIndex
        .builder()
        .workflowModuleIds(List.of("test-module"))
        .bpmnResourcePaths(List.of("bpmn-index-test/processes/not-there.bpmn"))
        .build();

    final var exception = assertThrows(
        IllegalStateException.class,
        () -> index.loadBpmnResources("bpmn-index-test/processes"));
    assertTrue(exception.getMessage().contains("not-there.bpmn"));

  }

}
