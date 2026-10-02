package io.vanillabp.integration.adapter.migration.workflowtask;

import java.util.UUID;

import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.spi.StoredKey;
import io.vanillabp.integration.spi.TaskDelivery;

/**
 * Builds the identity a processed task delivery is remembered by (see
 * {@link TaskDelivery#deliveryKey()}). The delivery ID an adapter reports only has to
 * be unique within its BPMS, so the key is qualified by everything which tells two
 * deliveries apart in an application talking to several of them:
 *
 * <pre>
 * &lt;adapterId&gt;|&lt;workflowModuleId&gt;|&lt;bpmnProcessId&gt;|&lt;taskEvent&gt;|&lt;deliveryId&gt;
 * </pre>
 *
 * The EVENT is part of it because one task instance may be delivered for more than
 * one lifecycle event (a user task created and later canceled) - those are two
 * deliveries of the same ID and each has its own outcome.
 * <p>
 * An adapter which reports no delivery ID gets a key of the same shape with
 * {@link #NOT_DEDUPLICATED} and a fresh random value where the ID would stand. Such a key
 * belongs to one row and to no delivery, so nothing is ever looked up by it, and a reader of
 * the store sees at the key itself that this row answers no repetition.
 * <p>
 * A key longer than {@link #MAX_LENGTH} characters is replaced by a hash of itself:
 * stores index the key, and unique-index key lengths are limited (MySQL: 3072 bytes,
 * which is 768 characters with utf8mb4). Hashing keeps long identifiers working and
 * costs only the readability of a record nobody can read anyway at that length. It is
 * {@link StoredKey} which does it, shared with the outbound idempotency key so both
 * hashed keys mean the same thing.
 * <p>
 * Why a processed delivery is written down under this key is decision 6 in the repository's
 * DECISIONS.md.
 */
public final class TaskDeliveryKey {

  /**
   * Up to this length a key is stored as it reads; a longer one is hashed.
   */
  public static final int MAX_LENGTH = 512;

  /**
   * What stands where the delivery ID would in the key of a delivery nobody deduplicates.
   * It is in the key so nobody has to read the rest of the row to see what the row is, and
   * it is not a valid delivery ID of any BPMS, so it can never collide with one.
   */
  public static final String NOT_DEDUPLICATED = "(not-deduplicated)";

  private TaskDeliveryKey() {

  }

  /**
   * The key of the given delivery, and whether a repetition of it is recognised by that key.
   * <p>
   * An adapter which reports no delivery ID cannot tell a repeated delivery from a new task,
   * so there is nothing to deduplicate. That used to be the end of it and no record was
   * written, which also dropped the two answers a record gives besides the deduplication:
   * which adapter holds the task, and which kind of id its id is. So a key is built either
   * way, and the one of a delivery without an ID carries {@link #NOT_DEDUPLICATED} plus a
   * random value: the row is unique, nothing is ever looked up by it, and no later delivery
   * can land on it.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param context The invocation context supplied by the adapter - it reports the
   *          adapter ID, the event and the delivery ID
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
