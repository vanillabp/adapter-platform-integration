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
 * resolves it in the {@link PhaseOperationRegistry} at dispatch time. Build
 * calls through {@link #of} (scheduling side - derives the idempotency key from
 * the operation) or {@link #forDispatch} (a store rebuilding a call from a
 * persisted entry).
 *
 * <p>
 * Which operations carry an idempotency key and which deliberately carry none is decision 2 in the
 * repository's DECISIONS.md.
 *
 * @param operation The NAME of the operation to execute (see
 *        {@link PhaseOperation#name()})
 * @param workflowModuleId The ID of the workflow module the workflow belongs to
 * @param bpmnProcessId The BPMN process ID of the workflow
 * @param workflowAggregateId The workflow-aggregate ID in serialized (String) form,
 *        or <code>null</code> for an operation which is not about one workflow
 * @param adapterId The ID of the elected BPMS adapter - set for
 *        {@link PhaseOperation#START_WORKFLOW} (the adapter elected in phase one
 *        is persisted and used in phase two), <code>null</code> for probing
 *        operations which determine the adapter at dispatch time
 * @param args Additional operation-specific arguments (empty for
 *        {@link PhaseOperation#START_WORKFLOW})
 * @param idempotencyKey The key deduplicating this call, derived by the operation
 *        when the call was built and bounded to
 *        {@link #MAX_IDEMPOTENCY_KEY_LENGTH} characters - {@link Optional#empty()}
 *        for operations which must not be deduplicated and for calls rebuilt by a
 *        store at dispatch time (where the key was persisted and is no longer
 *        needed)
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
   * The number of characters an idempotency key may have before it is replaced by a
   * hash of itself ({@link StoredKey}). It is the smallest limit of the stores
   * VanillaBP ships: gruelbox refuses a unique request ID longer than this before any
   * database sees it, while the <code>IDEMPOTENCY_KEY</code> column of the own stores
   * holds 512 characters. Bounding at the smallest one keeps a call schedulable
   * whichever store the application runs.
   */
  public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 250;

  /**
   * The number of characters the <code>AGGREGATE_ID</code> column of the outbox stores
   * holds. An id longer than this cannot be persisted at all - not as a hash either,
   * because the column is what somebody reads during support - so such a call is
   * refused where it is built, with a message naming the column instead of letting a
   * driver report a truncation in the middle of the application's transaction.
   */
  public static final int MAX_AGGREGATE_ID_LENGTH = 1024;

  /**
   * The {@link #args()} key carrying the task ID of
   * {@link PhaseOperation#COMPLETE_TASK} / {@link PhaseOperation#CANCEL_TASK}
   * calls. Part of the persisted contract - never change the literal.
   */
  public static final String ARG_TASK_ID = "taskId";

  /**
   * The {@link #args()} key carrying the BPMN error code of
   * {@link PhaseOperation#CANCEL_TASK} calls. Part of the persisted contract -
   * never change the literal.
   */
  public static final String ARG_BPMN_ERROR_CODE = "bpmnErrorCode";

  /**
   * The {@link #args()} key carrying the message name of
   * {@link PhaseOperation#CORRELATE_MESSAGE} /
   * {@link PhaseOperation#START_WORKFLOW_BY_MESSAGE} calls. Part of the
   * persisted contract - never change the literal.
   */
  public static final String ARG_MESSAGE_NAME = "messageName";

  /**
   * The {@link #args()} key carrying the optional correlation id of
   * {@link PhaseOperation#CORRELATE_MESSAGE} calls. Part of the persisted
   * contract - never change the literal.
   */
  public static final String ARG_CORRELATION_ID = "correlationId";

  /**
   * The {@link #args()} key carrying the signal name of
   * {@link PhaseOperation#SEND_SIGNAL} calls. Part of the persisted contract -
   * never change the literal.
   */
  public static final String ARG_SIGNAL_NAME = "signalName";

  /**
   * The {@link #args()} key carrying the activation a
   * {@link PhaseOperation#CORRELATE_MESSAGE} was planned in, absent where it was
   * planned outside any ({@link RunningActivation}). Part of the persisted contract -
   * never change the literal.
   * <p>
   * It travels for two reasons. The idempotency key is derived from it, which is what
   * keeps multi-instance siblings of one workflow aggregate from sharing a key (see
   * decision 23 in the repository's DECISIONS.md); and the adapter is handed it at
   * DISPATCH time, long after the thread which knew it has moved on, because a BPMS
   * deduplicating messages in a net of its own needs the same distinction there.
   */
  public static final String ARG_ACTIVATION_ID = "activationId";

  public PhaseTwoCall {
    Objects.requireNonNull(operation, "operation must not be null");
    Objects.requireNonNull(workflowModuleId, "workflowModuleId must not be null");
    Objects.requireNonNull(bpmnProcessId, "bpmnProcessId must not be null");
    // no requireNonNull: an operation which is not about ONE workflow carries no
    // aggregate ID (a broadcast signal)
    args = args == null ? Map.of() : Map.copyOf(args);
    idempotencyKey = Objects.requireNonNullElseGet(idempotencyKey, Optional::empty);
  }

  /**
   * Builds a call to be scheduled, deriving its idempotency key from the given
   * operation (see the derivation rules documented on {@link PhaseOperation}).
   *
   * @param operation The operation to execute
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow-aggregate ID in serialized form
   * @param adapterId The ID of the elected BPMS adapter or <code>null</code>
   * @param args Additional operation-specific arguments (may be
   *        <code>null</code>)
   * @return The call, ready to be handed to {@link PhaseTwoOutbox#schedule}
   * @throws IllegalArgumentException If the aggregate ID is longer than
   *         {@link #MAX_AGGREGATE_ID_LENGTH} characters (guiding message)
   */
  public static PhaseTwoCall of(
      final PhaseOperation operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId,
      final Map<String, String> args) {

    validateAggregateIdLength(operation, workflowModuleId, bpmnProcessId, workflowAggregateId);
    // the key is derived from the call itself, so the call is built twice: once
    // to derive from, once carrying the result
    final var withoutKey = new PhaseTwoCall(
        operation.name(), workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId, args, Optional.empty());
    // bounded HERE and not in the derivation rules, so no operation added later can
    // forget it - a key too long for the store fails the application's own
    // transaction, at the moment it starts a workflow or correlates a message
    final var boundedKey = operation
        .idempotencyKey()
        .derive(withoutKey)
        .map(key -> StoredKey.of(key, MAX_IDEMPOTENCY_KEY_LENGTH));
    return new PhaseTwoCall(
        withoutKey.operation(), withoutKey.workflowModuleId(), withoutKey.bpmnProcessId(), withoutKey
            .workflowAggregateId(), withoutKey
                .adapterId(), withoutKey.args(), boundedKey);

  }

  /**
   * Refuses an aggregate ID no store can hold. The check sits here because this is
   * where the ID is still at hand and where the stack trace still points at the
   * business code which owns it.
   */
  private static void validateAggregateIdLength(
      final PhaseOperation operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId) {

    if ((workflowAggregateId == null) || (workflowAggregateId.length() <= MAX_AGGREGATE_ID_LENGTH)) {
      return;
    }
    throw new IllegalArgumentException(
        """
            The ID of the workflow aggregate of BPMN process '%s' of workflow module '%s' is %d \
            characters long, which the phase-two outbox cannot store: its AGGREGATE_ID column holds \
            %d characters. The operation '%s' can therefore not be planned. Shorten the aggregate's \
            ID - it identifies the workflow in the BPMS as well - or widen the column in a migration \
            of your own and keep it wide in every later one. The ID begins with '%s'."""
            .formatted(
                bpmnProcessId,
                workflowModuleId,
                workflowAggregateId.length(),
                MAX_AGGREGATE_ID_LENGTH,
                operation.name(),
                workflowAggregateId.substring(0, 64)));

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
