package io.vanillabp.migration.test.delivery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "job-1")),
        TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "job-1")));

  }

  @Test
  @DisplayName("Adapter, workflow module, BPMN process, event and delivery ID all separate keys")
  public void everyQualifierSeparatesKeys() {

    final var key = TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "job-1"));

    // two BPMS may hand out the same ID - a migration runs both at the same time
    assertNotEquals(key, TaskDeliveryKey.of(MODULE, PROCESS, context("c7", TaskEvent.Event.CREATED, "job-1")));
    assertNotEquals(key, TaskDeliveryKey.of("other-module", PROCESS, context("c8", TaskEvent.Event.CREATED, "job-1")));
    assertNotEquals(key, TaskDeliveryKey.of(MODULE, "OtherProcess", context("c8", TaskEvent.Event.CREATED, "job-1")));
    // creation and cancellation of ONE user task share the ID and are two deliveries
    assertNotEquals(key, TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CANCELED, "job-1")));
    assertNotEquals(key, TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "job-2")));

  }

  @Test
  @DisplayName("An adapter reporting no delivery ID gets no key")
  public void withoutADeliveryIdThereIsNoKey() {

    assertNull(TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, null)));
    assertNull(TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "  ")));

  }

  @Test
  @DisplayName("The key format is pinned - records of a running installation are matched by it")
  public void theKeyFormatIsPinned() {

    assertEquals(
        "c8|test-module|TestProcess|CREATED|job-1",
        TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "job-1")));

    // past the boundary, pinned as a literal: the cap-and-hash is shared with the
    // outbound idempotency key now, and sharing it must not move this string by a
    // single character
    assertEquals(
        "sha256:4a4b5afafe8bbe0b1ef41a04bd55b156b580d888d653c970ab67c5bfc455fdc6",
        TaskDeliveryKey
            .of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, "j"
                .repeat(TaskDeliveryKey.MAX_LENGTH + 1))));

  }

  @Test
  @DisplayName("A key longer than the indexable length is hashed, and stays stable")
  public void anOversizedKeyIsHashed() {

    final var longId = "j".repeat(TaskDeliveryKey.MAX_LENGTH + 1);
    final var key = TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, longId));

    assertTrue(key.startsWith("sha256:"), "the key was hashed");
    assertTrue(key.length() <= TaskDeliveryKey.MAX_LENGTH, "the hash fits into the store's column");
    assertEquals(key, TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, longId)));
    assertNotEquals(
        key,
        TaskDeliveryKey.of(MODULE, PROCESS, context("c8", TaskEvent.Event.CREATED, longId
            + "x")));

  }

}
