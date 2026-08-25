package io.vanillabp.integration.adapter.migration.workflowtask;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
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
 * A key longer than {@link #MAX_LENGTH} characters is replaced by a hash of itself:
 * stores index the key, and unique-index key lengths are limited (MySQL: 3072 bytes,
 * which is 768 characters with utf8mb4). Hashing keeps long identifiers working and
 * costs only the readability of a record nobody can read anyway at that length.
 * <p>
 * Why a processed delivery is written down under this key is decision 6 in the repository's
 * DECISIONS.md.
 */
public final class TaskDeliveryKey {

  /**
   * Up to this length a key is stored as it reads; a longer one is hashed.
   */
  public static final int MAX_LENGTH = 512;

  private static final String HASH_PREFIX = "sha256:";

  private TaskDeliveryKey() {

  }

  /**
   * The key of the given delivery.
   *
   * @param workflowModuleId The workflow module ID
   * @param bpmnProcessId The BPMN process ID
   * @param context The invocation context supplied by the adapter - it reports the
   *          adapter ID, the event and the delivery ID
   * @return The key or <code>null</code> if the adapter reports no delivery ID (it
   *         cannot tell a redelivery from a new task, so nothing is remembered)
   */
  public static String of(
      final String workflowModuleId,
      final String bpmnProcessId,
      final TaskInvocationContext context) {

    final var deliveryId = context.getDeliveryId();
    if ((deliveryId == null) || deliveryId.isBlank()) {
      return null;
    }
    final var key = "%s|%s|%s|%s|%s"
        .formatted(
            context.getAdapterId(),
            workflowModuleId,
            bpmnProcessId,
            context.getTaskEvent(),
            deliveryId);
    return key.length() <= MAX_LENGTH
        ? key
        : hashed(key);

  }

  /**
   * Hashes a key exceeding {@link #MAX_LENGTH}. SHA-256 is available on every JVM;
   * the unreachable exception is wrapped rather than declared - a caller could not do
   * anything about it either.
   */
  private static String hashed(
      final String key) {

    try {
      final var digest = MessageDigest
          .getInstance("SHA-256")
          .digest(key.getBytes(StandardCharsets.UTF_8));
      return HASH_PREFIX + HexFormat.of().formatHex(digest);
    } catch (final NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available to hash a task delivery key!", e);
    }

  }

}
