package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.UUID;

import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.spi.StoredKey;
import io.vanillabp.integration.spi.TaskDelivery;

/**
 * Builds the identity under which a processed task delivery is remembered (see
 * {@link TaskDelivery#deliveryKey()}). The delivery ID which an adapter reports only has to
 * be unique within its BPMS. So the key is qualified by everything which tells two
 * deliveries apart in an application which talks to several BPMS:
 *
 * <pre>
 * &lt;adapterId&gt;|&lt;workflowModuleId&gt;|&lt;bpmnProcessId&gt;|&lt;taskEvent&gt;|&lt;deliveryId&gt;
 * </pre>
 *
 * The EVENT is part of it, because one task instance may be delivered for more than one
 * lifecycle event (a user task created, and later canceled). Those are two deliveries with
 * the same ID, and each has its own outcome.
 * <p>
 * An adapter which reports no delivery ID gets a key of the same shape. In place of the ID
 * it has {@link #NOT_DEDUPLICATED} and a new random value. Such a key belongs to one row and
 * to no delivery. So nothing is ever looked up by it, and a reader of the store sees from the
 * key itself that this row answers no repetition.
 * <p>
 * A key longer than {@link #MAX_LENGTH} characters is replaced by a hash of itself. Stores
 * index the key, and the key length of a unique index is limited (MySQL: 3072 bytes, which
 * is 768 characters with utf8mb4). Hashing keeps long identifiers working. It only costs
 * the readability of a record which nobody can read at that length anyway. {@link StoredKey}
 * does it, and it is shared with the outbound idempotency key. So both hashed keys mean the
 * same thing.
 * <p>
 * Decision 6 in the repository's DECISIONS.md says why a processed delivery is written down
 * under this key.
 */
public final class TaskDeliveryKey {

  /**
   * Up to this length a key is stored as it is. A longer one is hashed.
   */
  public static final int MAX_LENGTH = 512;

  /**
   * What stands in place of the delivery ID in the key of a delivery which nobody
   * deduplicates. It is in the key, so nobody has to read the rest of the row to see what the
   * row is. And it is not a valid delivery ID of any BPMS, so it can never collide with
   * one.
   */
  public static final String NOT_DEDUPLICATED = "(not-deduplicated)";

  private TaskDeliveryKey() {

  }

  /**
   * The key of the given delivery, and whether that key recognises a repetition of it.
   * <p>
   * An adapter which reports no delivery ID cannot tell a repeated delivery from a new task.
   * So there is nothing to deduplicate. It used to end there, and no record was written. That
   * also dropped the two other answers which a record gives besides the deduplication: which
   * adapter holds the task, and which kind of id its id is. So a key is built either way.
   * The key of a delivery without an ID has {@link #NOT_DEDUPLICATED} plus a random value.
   * The row is unique, nothing is ever looked up by it, and no later delivery can land on
   * it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param context The invocation context supplied by the adapter. It reports the adapter
   *          ID, the event and the delivery ID
   * @return The identity of the delivery, never <code>null</code>
   */
  public static TaskDeliveryIdentity of(
      final String workflowModuleId,
      final String bpmnProcessId,
      final TaskInvocationContext context) {

    final var deliveryId = context.getDeliveryId();
    final var deduplicates = (deliveryId != null) && !deliveryId.isBlank();
    final var key = "%s|%s|%s|%s|%s"
        .formatted(
            context.getAdapterId(),
            workflowModuleId,
            bpmnProcessId,
            context.getTaskEvent(),
            deduplicates
                ? deliveryId
                : NOT_DEDUPLICATED + UUID.randomUUID());
    return new TaskDeliveryIdentity(StoredKey.of(key, MAX_LENGTH), deduplicates);

  }

}
