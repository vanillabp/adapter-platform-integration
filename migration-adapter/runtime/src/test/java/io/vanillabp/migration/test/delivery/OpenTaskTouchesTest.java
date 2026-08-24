package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.delivery.OpenTaskTouches;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * How the keys of the open tasks a BPMS redelivered reach their store. The
 * bundling itself is what is pinned here - that nothing is written while a delivery is
 * being processed, that the store sees blocks of at most
 * {@link OpenTaskTouches#BLOCK_SIZE} keys however many arrive, and that a failing block
 * neither kills the flush nor the keys which come after it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OpenTaskTouchesTest {

  private final List<List<String>> blocks = new ArrayList<>();

  private OpenTaskTouches testee() {

    return new OpenTaskTouches("VANILLABP_TASK_DELIVERY", blocks::add);

  }

  @Test
  @DisplayName("Remembering a key writes nothing - the flush does")
  public void rememberingWritesNothing() {

    final var testee = testee();

    testee.remember("job-1");
    testee.remember("job-2");
    testee.remember("job-1");

    assertTrue(blocks.isEmpty(), "a redelivery must not write in the transaction it runs in");
    assertEquals(2, testee.size(), "the same task redelivered twice is one key");

    assertEquals(2, testee.flush());
    assertEquals(1, blocks.size());
    assertEquals(List.of("job-1", "job-2"), blocks.getFirst().stream().sorted().toList());
    assertEquals(0, testee.size(), "what was written is not written again");

  }

  @Test
  @DisplayName("Nothing to write is no round trip at all")
  public void anEmptyFlushDoesNothing() {

    assertEquals(0, testee().flush());
    assertTrue(blocks.isEmpty());

  }

  @Test
  @DisplayName("More keys than the block size are written in blocks of at most that size")
  public void moreKeysThanTheBlockSizeAreWrittenInBlocks() {

    final var testee = testee();
    final var keys = OpenTaskTouches.BLOCK_SIZE + (OpenTaskTouches.BLOCK_SIZE / 2);
    for (var i = 0; i < keys; i++) {
      testee.remember("job-"
          + i);
    }

    assertEquals(keys, testee.flush());

    assertEquals(2, blocks.size(), "one full block and the remainder");
    assertEquals(OpenTaskTouches.BLOCK_SIZE, blocks.getFirst().size());
    assertEquals(OpenTaskTouches.BLOCK_SIZE / 2, blocks.getLast().size());
    assertEquals(
        keys,
        blocks.stream().flatMap(List::stream).distinct().count(),
        "every key reached the store exactly once");

  }

  @Test
  @DisplayName("A block the store refuses costs its own keys, not the ones after it")
  public void aFailingBlockDoesNotSwallowTheRest() {

    final var written = new ArrayList<String>();
    final var testee = new OpenTaskTouches("VANILLABP_TASK_DELIVERY", block -> {
      if (block.size() == OpenTaskTouches.BLOCK_SIZE) {
        throw new IllegalStateException("the database was gone for a moment");
      }
      written.addAll(block);
    });
    for (var i = 0; i < (OpenTaskTouches.BLOCK_SIZE + 10); i++) {
      testee.remember("job-"
          + i);
    }

    // the first block throws, so the flush ends there - the keys it did not reach stay
    assertEquals(0, testee.flush());
    assertEquals(10, testee.size(), "what was not collected yet is still remembered");

    assertEquals(10, testee.flush(), "and it is written by the next run");
    assertEquals(10, written.size());

  }

  @Test
  @DisplayName("The memory is bounded - a hint lost costs a record, never the application")
  public void theMemoryIsBounded() {

    final var testee = testee();
    for (var i = 0; i < (OpenTaskTouches.MAX_REMEMBERED + 100); i++) {
      testee.remember("job-"
          + i);
    }

    assertEquals(OpenTaskTouches.MAX_REMEMBERED, testee.size());

    testee.remember(null);
    assertEquals(OpenTaskTouches.MAX_REMEMBERED, testee.size(), "and nothing without a key");

  }

}
