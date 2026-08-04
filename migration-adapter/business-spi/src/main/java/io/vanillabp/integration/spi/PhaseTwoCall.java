package io.vanillabp.integration.spi;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable description of the second phase of a two-phase committed BPMS call,
 * scheduled via {@link PhaseTwoOutbox#schedule(PhaseTwoCall)} within the local
 * transaction and dispatched after that transaction was committed.
 * <p>
 * The workflow-aggregate ID is carried in its serialized {@link String} form - the
 * serialized form is the ONLY form in transport. Conversion back to the aggregate's
 * ID type happens exactly once, at dispatch time, by the core's router using a
 * converter registered by the platform integration.
 *
 * @param operation The operation to execute
 * @param workflowModuleId The ID of the workflow module the workflow belongs to
 * @param bpmnProcessId The BPMN process ID of the workflow
 * @param workflowAggregateId The workflow-aggregate ID in serialized (String) form
 * @param adapterId The ID of the elected BPMS adapter - set for
 *        {@link PhaseTwoOperation#START_WORKFLOW} (the adapter elected in phase one
 *        is persisted and used in phase two), <code>null</code> for future probing
 *        operations which determine the adapter at dispatch time
 * @param args Additional operation-specific arguments (empty for
 *        {@link PhaseTwoOperation#START_WORKFLOW})
 */
public record PhaseTwoCall(
                           PhaseTwoOperation operation,
                           String workflowModuleId,
                           String bpmnProcessId,
                           String workflowAggregateId,
                           String adapterId,
                           Map<String, String> args) {

  /**
   * The {@link #args()} key carrying the task ID of
   * {@link PhaseTwoOperation#COMPLETE_TASK} / {@link PhaseTwoOperation#CANCEL_TASK}
   * calls. Part of the persisted contract - never change the literal.
   */
  public static final String ARG_TASK_ID = "taskId";

  /**
   * The {@link #args()} key carrying the BPMN error code of
   * {@link PhaseTwoOperation#CANCEL_TASK} calls. Part of the persisted contract -
   * never change the literal.
   */
  public static final String ARG_BPMN_ERROR_CODE = "bpmnErrorCode";

  /**
   * The {@link #args()} key carrying the message name of
   * {@link PhaseTwoOperation#CORRELATE_MESSAGE} /
   * {@link PhaseTwoOperation#START_WORKFLOW_BY_MESSAGE} calls. Part of the
   * persisted contract - never change the literal.
   */
  public static final String ARG_MESSAGE_NAME = "messageName";

  /**
   * The {@link #args()} key carrying the optional correlation id of
   * {@link PhaseTwoOperation#CORRELATE_MESSAGE} calls. Part of the persisted
   * contract - never change the literal.
   */
  public static final String ARG_CORRELATION_ID = "correlationId";

  public PhaseTwoCall {
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(workflowModuleId, "workflowModuleId must not be null");
    Objects.requireNonNull(bpmnProcessId, "bpmnProcessId must not be null");
    Objects.requireNonNull(workflowAggregateId, "workflowAggregateId must not be null");
    args = args == null ? Map.of() : Map.copyOf(args);
  }

  /**
   * The idempotency key of this call, derived per operation - see the derivation
   * rules documented on {@link PhaseTwoOperation}.
   *
   * @return The idempotency key or {@link Optional#empty()} if the operation must
   *         not be deduplicated
   */
  public Optional<String> idempotencyKey() {

    return operation.idempotencyKey(this);

  }

  /**
   * Serializes an args map into a single String for stores flattening calls into
   * scalar columns/parameters (form-encoding: URL-encoded keys/values joined by
   * <code>=</code> and <code>&amp;</code>). Part of the persisted contract - never
   * change the encoding for existing operations.
   *
   * @param args The args map (may be empty)
   * @return The serialized form or <code>null</code> for an empty map
   */
  public static String serializeArgs(
      final Map<String, String> args) {

    if ((args == null) || args.isEmpty()) {
      return null;
    }
    return args
        .entrySet()
        .stream()
        .map(entry -> java.net.URLEncoder
            .encode(entry.getKey(), java.nio.charset.StandardCharsets.UTF_8)
            + "="
            + java.net.URLEncoder.encode(entry.getValue(), java.nio.charset.StandardCharsets.UTF_8))
        .collect(java.util.stream.Collectors.joining("&"));

  }

  /**
   * Deserializes an args map serialized by {@link #serializeArgs(Map)}.
   *
   * @param serializedArgs The serialized form (may be <code>null</code> or blank)
   * @return The args map (never <code>null</code>)
   */
  public static Map<String, String> deserializeArgs(
      final String serializedArgs) {

    if ((serializedArgs == null) || serializedArgs.isBlank()) {
      return Map.of();
    }
    final var result = new java.util.LinkedHashMap<String, String>();
    for (final var pair : serializedArgs.split("&")) {
      final var separator = pair.indexOf('=');
      result.put(
          java.net.URLDecoder
              .decode(pair.substring(0, separator), java.nio.charset.StandardCharsets.UTF_8),
          java.net.URLDecoder
              .decode(pair.substring(separator + 1), java.nio.charset.StandardCharsets.UTF_8));
    }
    return result;

  }

}
