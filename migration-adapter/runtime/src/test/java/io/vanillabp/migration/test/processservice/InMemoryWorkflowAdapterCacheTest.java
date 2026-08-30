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
 * eviction beyond the size bound, expiry after the time-to-live, invalidation, and the
 * shorter lifetime of the hint of a workflow which ended.
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
  @DisplayName("The hint of an ended workflow answers, and expires on its own lifetime")
  public void endedEntriesExpireEarlier() throws Exception {

    // the same reasoning as in the expiry test: hundreds of milliseconds, not tens
    final var cache = new InMemoryWorkflowAdapterCache(10, Duration.ofHours(1), Duration.ofMillis(500));

    cache.putEnded(MODULE, PROCESS, "42", "adapter-a");

    assertEquals(
        "adapter-a",
        cache.get(MODULE, PROCESS, "42").orElseThrow(),
        "an operation arriving after the end still gets the adapter which held the workflow");
    assertEquals(1, cache.endedSize(), "and the entry is counted apart from the living ones");

    Thread.sleep(800);
    assertTrue(
        cache.get(MODULE, PROCESS, "42").isEmpty(),
        "the hint of an ended workflow must not wait for the time-to-live of a living one");
    assertEquals(0, cache.endedSize(), "an expired entry is gone from the count as well");

    // and so is one an invalidation takes
    cache.putEnded(MODULE, PROCESS, "43", "adapter-a");
    cache.invalidate(MODULE, PROCESS, "43");
    assertEquals(0, cache.endedSize());

  }

  @Test
  @DisplayName("A workflow started on the same aggregate outlives the mark of the one before it")
  public void aSecondWorkflowOnTheSameAggregateWins() {

    // the key is the aggregate and not the instance, so both events write the same entry
    final var cache = new InMemoryWorkflowAdapterCache(10, Duration.ofHours(1), Duration.ofMinutes(5));

    // the end arrives first, then the second workflow starts elsewhere
    cache.putEnded(MODULE, PROCESS, "42", "adapter-a");
    cache.put(MODULE, PROCESS, "42", "adapter-b");
    assertEquals("adapter-b", cache.get(MODULE, PROCESS, "42").orElseThrow());
    assertEquals(0, cache.endedSize(), "the new workflow's hint is a living one");

    // and the other way round: the notification of the ended workflow arrives late
    cache.putEnded(MODULE, PROCESS, "42", "adapter-a");
    assertEquals(
        "adapter-b",
        cache.get(MODULE, PROCESS, "42").orElseThrow(),
        "a late end must not point the hint back at the adapter of the workflow before it");
    assertEquals(0, cache.endedSize());

  }

  @Test
  @DisplayName("Entries expire after the time-to-live")
  public void expiry() throws Exception {

    // a TTL of a few hundred milliseconds, not tens: under load (a full build runs
    // tests in parallel with other JVMs) the get right after the put would
    // otherwise race the expiry and fail spuriously
    final var cache = new InMemoryWorkflowAdapterCache(10, Duration.ofMillis(500));

    cache.put(MODULE, PROCESS, "42", "adapter-a");
    assertEquals("adapter-a", cache.get(MODULE, PROCESS, "42").orElseThrow());

    Thread.sleep(800);
    assertTrue(cache.get(MODULE, PROCESS, "42").isEmpty(), "the entry must expire");

  }

}
