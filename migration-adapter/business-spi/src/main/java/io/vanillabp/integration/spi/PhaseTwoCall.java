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
 * The operation is carried by NAME: stores persist it, and the core's router
 * resolves it in the {@link PhaseTwoOperationRegistry} at dispatch time. Build
 * calls through {@link #of} (scheduling side - derives the idempotency key from
 * the operation) or {@link #forDispatch} (a store rebuilding a call from a
 * persisted entry).
 *
 * @param operation The NAME of the operation to execute (see
 *        {@link PhaseTwoOperation#name()})
 * @param workflowModuleId The ID of the workflow module the workflow belongs to
 * @param bpmnProcessId The BPMN process ID of the workflow
 * @param workflowAggregateId The workflow-aggregate ID in serialized (String) form,
 *        or <code>null</code> for an operation which is not about one workflow
 * @param adapterId The ID of the elected BPMS adapter - set for
 *        {@link PhaseTwoOperation#START_WORKFLOW} (the adapter elected in phase one
 *        is persisted and used in phase two), <code>null</code> for probing
 *        operations which determine the adapter at dispatch time
 * @param args Additional operation-specific arguments (empty for
 *        {@link PhaseTwoOperation#START_WORKFLOW})
 * @param idempotencyKey The key deduplicating this call, derived by the operation
 *        when the call was built - {@link Optional#empty()} for operations which
 *        must not be deduplicated and for calls rebuilt by a store at dispatch
 *        time (where the key was persisted and is no longer needed)
 */
public record PhaseTwoCall(
                           String operation,
                           String workflowModuleId,
                           String bpmnProcessId,
                           String workflowAggregateId,
                           String adapterId,
                           Map<String, String> args,
                           Optional<String> idempotencyKey) {

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

  /**
   * The {@link #args()} key carrying the signal name of
   * {@link PhaseTwoOperation#SEND_SIGNAL} calls. Part of the persisted contract -
   * never change the literal.
   */
  public static final String ARG_SIGNAL_NAME = "signalName";

  public PhaseTwoCall {
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(workflowModuleId, "workflowModuleId must not be null");
    Objects.requireNonNull(bpmnProcessId, "bpmnProcessId must not be null");
    // no requireNonNull: an operation which is not about ONE workflow carries no
    // aggregate ID (a broadcast signal)
    args = args == null ? Map.of() : Map.copyOf(args);
    idempotencyKey = idempotencyKey == null ? Optional.empty() : idempotencyKey;
  }

  /**
   * Builds a call to be scheduled, deriving its idempotency key from the given
   * operation (see the derivation rules documented on {@link PhaseTwoOperation}).
   *
   * @param operation The operation to execute
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow-aggregate ID in serialized form
   * @param adapterId The ID of the elected BPMS adapter or <code>null</code>
   * @param args Additional operation-specific arguments (may be
   *        <code>null</code>)
   * @return The call, ready to be handed to {@link PhaseTwoOutbox#schedule}
   */
  public static PhaseTwoCall of(
      final PhaseTwoOperation operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId,
      final Map<String, String> args) {

    // the key is derived from the call itself, so the call is built twice: once
    // to derive from, once carrying the result
    final var withoutKey = new PhaseTwoCall(
        operation.name(), workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId, args, Optional.empty());
    return new PhaseTwoCall(
        withoutKey.operation(), withoutKey.workflowModuleId(), withoutKey.bpmnProcessId(), withoutKey
            .workflowAggregateId(), withoutKey
                .adapterId(), withoutKey.args(), operation.idempotencyKey().derive(withoutKey));

  }

  /**
   * Rebuilds a call from a persisted outbox entry. The idempotency key is not
   * part of it: the store persisted it when the entry was written and nothing
   * downstream of the store reads it again.
   *
   * @param operation The persisted operation NAME
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow-aggregate ID in serialized form
   * @param adapterId The persisted adapter ID or <code>null</code>
   * @param args The persisted arguments (may be <code>null</code>)
   * @return The call, ready to be handed to the core's router
   */
  public static PhaseTwoCall forDispatch(
      final String operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId,
      final Map<String, String> args) {

    return new PhaseTwoCall(
        operation, workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId, args, Optional.empty());

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
