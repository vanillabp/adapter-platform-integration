package io.vanillabp.migration.test.processservice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.processservice.InMemoryWorkflowAdapterCache;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The bounds of the default in-memory {@link InMemoryWorkflowAdapterCache}: LRU
 * eviction beyond the size bound, expiry after the time-to-live, invalidation.
 */
@ExtendWith(SuppressOutputExtension.class)
public class InMemoryWorkflowAdapterCacheTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  @Test
  @DisplayName("put/get/invalidate round-trip")
  public void roundTrip() {

    final var cache = new InMemoryWorkflowAdapterCache();

    assertTrue(cache.get(MODULE, PROCESS, "42").isEmpty());

    cache.put(MODULE, PROCESS, "42", "adapter-a");
    assertEquals("adapter-a", cache.get(MODULE, PROCESS, "42").orElseThrow());
    assertTrue(cache.get(MODULE, PROCESS, "43").isEmpty(), "another aggregate is another key");
    assertTrue(cache.get(MODULE, "OtherProcess", "42").isEmpty(), "another process is another key");

    cache.invalidate(MODULE, PROCESS, "42");
    assertTrue(cache.get(MODULE, PROCESS, "42").isEmpty());
    // invalidating an absent entry is a no-op
    cache.invalidate(MODULE, PROCESS, "42");

  }

  @Test
  @DisplayName("The least recently used entry is evicted beyond the size bound")
  public void lruEviction() {

    final var cache = new InMemoryWorkflowAdapterCache(2, Duration.ofHours(1));

    cache.put(MODULE, PROCESS, "1", "adapter-a");
    cache.put(MODULE, PROCESS, "2", "adapter-a");
    // touch "1" so "2" is the least recently used entry
    cache.get(MODULE, PROCESS, "1");
    cache.put(MODULE, PROCESS, "3", "adapter-a");

    assertTrue(cache.get(MODULE, PROCESS, "1").isPresent());
    assertTrue(cache.get(MODULE, PROCESS, "2").isEmpty(), "the least recently used entry is evicted");
    assertTrue(cache.get(MODULE, PROCESS, "3").isPresent());

  }

  @Test
  @DisplayName("Entries expire after the time-to-live")
  public void expiry() throws Exception {

    final var cache = new InMemoryWorkflowAdapterCache(10, Duration.ofMillis(50));

    cache.put(MODULE, PROCESS, "42", "adapter-a");
    assertEquals("adapter-a", cache.get(MODULE, PROCESS, "42").orElseThrow());

    Thread.sleep(80);
    assertTrue(cache.get(MODULE, PROCESS, "42").isEmpty(), "the entry must expire");

  }

}
