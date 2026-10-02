package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.adapter.migration.workflowtask.TaskDeliveryKey;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.service.TaskEvent;

/**
 * What tells two task deliveries apart: the delivery ID an adapter reports is
 * unique within ITS BPMS only, so the key is qualified by adapter, workflow module, BPMN
 * process and event - and it is hashed where it would outgrow what a store can index.
 * <p>
 * A delivery whose adapter reports no ID is in here too. It gets a key of its own rather
 * than none, because the row it writes answers two questions besides the deduplication, and
 * that key must never be the key of a second delivery.
 */
@ExtendWith(SuppressOutputExtension.class)
public class TaskDeliveryKeyTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "TestProcess";

  private TaskInvocationContext context(
      final String adapterId,
      final TaskEvent.Event event,
      final String deliveryId) {

    return new TaskInvocationContext() {

      @Override
      public String getAdapterId() {
        return adapterId;
      }

      @Override
      public String getTaskDefinition() {
        return "task";
      }

      @Override
      public String getWorkflowAggregateId() {
        return "4711";
      }

      @Override
      public TaskEvent.Event getTaskEvent() {
        return event;
      }

      @Override
      public String getDeliveryId() {
        return deliveryId;
      }

    };

  }

  @Test
  @DisplayName("The same delivery yields the same key")
  public void theSameDeliveryYieldsTheSameKey() {

    assertEquals(
        TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "job-1")).key(),
        TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "job-1")).key());

  }

  @Test
  @DisplayName("Adapter, workflow module, BPMN process, event and delivery ID all separate keys")
  public void everyQualifierSeparatesKeys() {

    final var key = TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "job-1")).key();

    // two BPMS may hand out the same ID - a migration runs both at the same time
    assertNotEquals(key, TaskDeliveryKey.of(MODULE, PROCESS, context("c7", TaskEvent.Event.CREATED, "job-1")).key());
    assertNotEquals(
        key,
        TaskDeliveryKey.of("other-module", PROCESS, context("c8", TaskEvent.Event.CREATED, "job-1")).key());
    assertNotEquals(key,
        TaskDeliveryKey.of(MODULE, "OtherProcess", context("c8", TaskEvent.Event.CREATED, "job-1")).key());
    // creation and cancellation of ONE user task share the ID and are two deliveries
    assertNotEquals(key, TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CANCELED, "job-1")).key());
    assertNotEquals(key, TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "job-2")).key());

  }

  @Test
  @DisplayName("An adapter reporting no delivery ID gets a key which deduplicates nothing")
  public void withoutADeliveryIdTheKeyDeduplicatesNothing() {

    final var identity = TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, null));

    assertFalse(identity.deduplicates(), "there is no ID to recognise a repetition by");
    assertTrue(
        identity.key().startsWith("c8|test-module|TestProcess|CREATED|(not-deduplicated)"),
        "a reader of the store sees it at the key: "
            + identity.key());
    // the same delivery asked twice is two rows, which is what makes the key unique
    assertNotEquals(
        identity.key(),
        TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, null)).key());
    assertFalse(
        TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "  ")).deduplicates(),
        "a blank ID is no ID");

  }

  @Test
  @DisplayName("A delivery ID of its own is what makes a key deduplicate")
  public void aDeliveryIdIsWhatMakesAKeyDeduplicate() {

    assertTrue(
        TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "job-1")).deduplicates());

  }

  @Test
  @DisplayName("The key format is pinned - records of a running installation are matched by it")
  public void theKeyFormatIsPinned() {

    assertEquals(
        "c8|test-module|TestProcess|CREATED|job-1",
        TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "job-1")).key());

    // past the boundary, pinned as a literal: the cap-and-hash is shared with the
    // outbound idempotency key now, and sharing it must not move this string by a
    // single character
    assertEquals(
        "sha256:4a4b5afafe8bbe0b1ef41a04bd55b156b580d888d653c970ab67c5bfc455fdc6",
        TaskDeliveryKey
            .of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "j"
                .repeat(TaskDeliveryKey.MAX_LENGTH + 1)))
            .key());

  }

  @Test
  @DisplayName("A key longer than the indexable length is hashed, and stays stable")
  public void anOversizedKeyIsHashed() {

    final var longId = "j".repeat(TaskDeliveryKey.MAX_LENGTH + 1);
    final var key = TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, longId)).key();

    assertTrue(key.startsWith("sha256:"), "the key was hashed");
    assertTrue(key.length() <= TaskDeliveryKey.MAX_LENGTH, "the hash fits into the store's column");
    assertEquals(key, TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, longId)).key());
    assertNotEquals(
        key,
        TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, longId
            + "x")).key());

  }

}
