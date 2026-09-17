package io.vanillabp.integration.spi;

import java.util.Arrays;
import java.util.Comparator;
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
 * @param payload The bytes the caller wants the handler to see at dispatch time, or
 *        <code>null</code> for a call without one. They do NOT travel in the outbox
 *        entry: the store writes them into a {@link PhaseTwoPayloadStore} in the same
 *        transaction and the entry names them by
 *        {@link #ARG_PAYLOAD_REFERENCE}, which is what keeps an outbox entry a row of
 *        identifiers
 */
public record PhaseTwoCall(
                           String operation,
                           String workflowModuleId,
                           String bpmnProcessId,
                           String workflowAggregateId,
                           String adapterId,
                           Map<String, String> args,
                           Optional<String> idempotencyKey,
                           byte[] payload) {

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
   * The number of characters the <code>ARGS</code> column of the VanillaBP JDBC store
   * holds, measured against the SERIALIZED form ({@link #serializeArgs(Map)}), because
   * that is what is persisted: a value well under the limit can exceed it once its
   * characters are encoded.
   * <p>
   * Enforced for every store, although gruelbox serializes a call differently and may
   * have no such bound. An application which moves from one store to the other must not
   * discover this limit at that moment, which is the same reasoning that bounds the
   * idempotency key at the smallest limit of the stores rather than at the one the store
   * in use happens to have.
   */
  public static final int MAX_ARGS_LENGTH = 2048;

  /**
   * The largest payload a call may carry, in bytes. One mebibyte, which is wide enough
   * for the state of a workflow aggregate written as JSON and narrow enough that a
   * database holding a backlog of them stays a database somebody can back up.
   * <p>
   * It stands HERE and in no store, so an application meets the same limit whichever
   * store it runs and never discovers a narrower one by moving from one to the other -
   * the reasoning that bounds the idempotency key at the smallest limit of the stores.
   * MongoDB's own limit of 16 MB per document is far above it, and so is what a
   * <code>BLOB</code> holds on every database VanillaBP ships statements for.
   */
  public static final int MAX_PAYLOAD_SIZE = 1024 * 1024;

  /**
   * The number of characters an auditing id may have
   * ({@link #askingForTheStateOfTheEvent(String)}). It rides the <code>ARGS</code>
   * column together with the arguments of the call, so it is bounded well below what
   * that column holds ({@link #MAX_ARGS_LENGTH}) and for the same reason the
   * idempotency key is bounded: a value which names a state is a revision number or a
   * timestamp, and one which is longer than this is something else.
   */
  public static final int MAX_AUDITING_ID_LENGTH = 250;

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
   * The {@link #args()} key carrying the reference of the call's payload, written by
   * {@link #of} where a payload was passed and absent where none was. Part of the
   * persisted contract - never change the literal.
   * <p>
   * The reference and not the bytes: a payload lies in a store of its own
   * ({@link PhaseTwoPayloadStore}) and the entry names it, so the columns an outbox
   * entry is made of stay the identifiers they were meant to be. It travels in the
   * args because that is what every store already persists and hands back at dispatch
   * time, and because a reference IS an identifier. It is added AFTER the idempotency
   * key was derived, so no derivation rule can ever see it - a fresh reference per call
   * would otherwise make every call unique and deduplicate nothing.
   */
  public static final String ARG_PAYLOAD_REFERENCE = "payloadReference";

  /**
   * The {@link #args()} key carrying the auditing id of the aggregate state the call
   * wants to see at its dispatch, written by
   * {@link #askingForTheStateOfTheEvent(String)} and absent where the call takes the
   * state of the moment it is dispatched in. Part of the persisted contract - never
   * change the literal.
   * <p>
   * It is added AFTER the idempotency key was derived, for the reason given at
   * {@link #ARG_PAYLOAD_REFERENCE}: which state a call wants to read says nothing about
   * whether it is the same operation as another one, so no derivation rule may see it.
   */
  public static final String ARG_AUDITING_ID = "auditingId";

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
    // copied on the way in and on the way out, so nobody changes bytes another thread
    // is about to write to a database
    payload = payload == null ? null : payload.clone();
  }

  /**
   * The payload of this call, or <code>null</code> where it carries none.
   *
   * @return A copy of the bytes, so a caller reading them cannot change what the call
   *         holds
   */
  public byte[] payload() {

    return payload == null ? null : payload.clone();

  }

  /**
   * Whether this call carries a payload.
   *
   * @return Whether there are bytes to store, respectively bytes to hand to the handler
   */
  public boolean hasPayload() {

    return payload != null;

  }

  /**
   * The reference the payload of this call is stored under, or <code>null</code> where
   * the call carries none. It is what a {@link PhaseTwoPayloadStore} is asked with at
   * dispatch time.
   *
   * @return The reference or <code>null</code>
   */
  public String payloadReference() {

    return args.get(ARG_PAYLOAD_REFERENCE);

  }

  /**
   * The same call carrying the bytes a store read for its reference - what a store
   * builds at dispatch time once it looked the payload up.
   *
   * @param payload The bytes read from the payload store
   * @return A copy of this call carrying them
   */
  public PhaseTwoCall withPayload(
      final byte[] payload) {

    return new PhaseTwoCall(
        operation, workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId, args, idempotencyKey, payload);

  }

  /**
   * The auditing id of the aggregate state this call wants to see when it is
   * dispatched, or <code>null</code> where it wants the state of that moment.
   *
   * @return The auditing id or <code>null</code>
   */
  public String auditingId() {

    return args.get(ARG_AUDITING_ID);

  }

  /**
   * The same call, asking to see the aggregate as it was at the moment this call was
   * planned instead of as it is when it is dispatched.
   * <p>
   * Between the two moments lies the time the entry waits, and that is milliseconds
   * while everything works and days once a receiver is gone. Which of the two states is
   * the right one belongs to the single call, not to the application: an entry which
   * syncs the aggregate INTO the BPMS needs the current state, because the engine is
   * where the case goes on and older values written back there are wrong; an entry
   * which reports what happened wants the state of the event, because that is what the
   * report is about.
   * <p>
   * The id comes from {@link AggregatePersistenceAware#getAuditingId(Object)} and is
   * collected while the caller's transaction is still open. At the dispatch the
   * aggregate is loaded through
   * {@link AggregatePersistenceAware#loadByIdAndAuditingId(Object, String)}, which
   * answers the current state where the application keeps no history at all. So a call
   * asking for the state of the event in an application without auditing behaves
   * exactly like one which does not ask, and a call which does not ask costs nothing
   * either way - it reads no history.
   * <p>
   * Why the choice sits on the call rather than in a setting, and why the operations of
   * VanillaBP cannot make it, is decision 63 in the repository's DECISIONS.md.
   *
   * @param auditingId The auditing id of the state to be seen at the dispatch. A
   *          <code>null</code> - which is what an application without auditing answers
   *          - leaves the call unchanged
   * @return The call, asking for that state
   * @throws IllegalArgumentException If this is one of VanillaBP's own operations,
   *           which always sync into the BPMS, or if the auditing id is longer than
   *           {@link #MAX_AUDITING_ID_LENGTH} characters (guiding message)
   */
  public PhaseTwoCall askingForTheStateOfTheEvent(
      final String auditingId) {

    if (auditingId == null) {
      return this;
    }
    validateAuditingId(auditingId);
    final var withAuditingId = new java.util.LinkedHashMap<>(args);
    withAuditingId.put(ARG_AUDITING_ID, auditingId);
    // the id rides the same column as the arguments, so what fitted before may not fit
    // with it - measured again rather than trusted, the way the arguments themselves are
    validateArgsLength(operation, workflowModuleId, bpmnProcessId, withAuditingId);
    return new PhaseTwoCall(
        operation, workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId, withAuditingId, idempotencyKey, payload);

  }

  /**
   * Refuses an auditing id which would be read by nobody or stored by nothing.
   * <p>
   * The first half is the rule of the story behind this method: an operation of
   * VanillaBP's own tells the BPMS what the workflow is to do next, and it reads the
   * aggregate to do so. Served with the state of an event which is a day old, it would
   * write a day-old value into the engine the case goes on in. So the state of the
   * event is for the entries which report, and asking for it here is refused where it
   * is asked rather than quietly ignored at the dispatch.
   */
  private void validateAuditingId(
      final String auditingId) {

    if (PhaseOperation.coreOperation(operation).isPresent()) {
      throw new IllegalArgumentException(
          """
              The operation '%s' of BPMN process '%s' of workflow module '%s' cannot ask for the \
              state of the event: it is one of VanillaBP's own operations, and those write to the \
              BPMS. The BPMS is where the workflow goes on, so it is told what the aggregate says \
              now and not what it said when the entry was planned. Ask for the state of the event \
              in an operation of your own, which reports rather than writes."""
              .formatted(operation, bpmnProcessId, workflowModuleId));
    }
    if (auditingId.length() > MAX_AUDITING_ID_LENGTH) {
      throw new IllegalArgumentException(
          """
              The auditing id of operation '%s' of BPMN process '%s' of workflow module '%s' is %d \
              characters long, and a phase-two call carries at most %d characters. It travels in \
              the ARGS column beside the arguments of the call and names one state of one \
              aggregate, so it is a revision number or a timestamp. Answer a shorter id in \
              getAuditingId of your AggregatePersistenceAware implementation. The id begins with \
              '%s'."""
              .formatted(
                  operation,
                  bpmnProcessId,
                  workflowModuleId,
                  auditingId.length(),
                  MAX_AUDITING_ID_LENGTH,
                  auditingId.substring(0, 64)));
    }

  }

  /**
   * Two calls are the same call when they describe the same operation with the same
   * bytes. Written out because the equality a record generates compares an array by its
   * identity, which would make two calls carrying the very same payload unequal.
   */
  @Override
  public boolean equals(
      final Object other) {

    if (this == other) {
      return true;
    }
    if (!(other instanceof final PhaseTwoCall call)) {
      return false;
    }
    return Objects.equals(operation, call.operation) && Objects.equals(workflowModuleId,
        call.workflowModuleId) && Objects.equals(bpmnProcessId, call.bpmnProcessId) && Objects
            .equals(workflowAggregateId, call.workflowAggregateId) && Objects.equals(adapterId,
                call.adapterId) && Objects.equals(args, call.args) && Objects.equals(idempotencyKey,
                    call.idempotencyKey) && Arrays.equals(payload, call.payload);

  }

  @Override
  public int hashCode() {

    return Objects
        .hash(
            operation,
            workflowModuleId,
            bpmnProcessId,
            workflowAggregateId,
            adapterId,
            args,
            idempotencyKey,
            Arrays.hashCode(payload));

  }

  /**
   * Names the payload by its length and not by its bytes: the bytes belong to the
   * application, and a log line printing them is a log line leaking them.
   */
  @Override
  public String toString() {

    return "PhaseTwoCall[operation=%s, workflowModuleId=%s, bpmnProcessId=%s, workflowAggregateId=%s, adapterId=%s, args=%s, idempotencyKey=%s, payload=%s]"
        .formatted(
            operation,
            workflowModuleId,
            bpmnProcessId,
            workflowAggregateId,
            adapterId,
            args,
            idempotencyKey,
            payload == null ? "none" : "%d bytes".formatted(payload.length));

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
   *         {@link #MAX_AGGREGATE_ID_LENGTH} characters, or if the serialized args are
   *         longer than {@link #MAX_ARGS_LENGTH} characters (guiding message)
   */
  public static PhaseTwoCall of(
      final PhaseOperation operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId,
      final Map<String, String> args) {

    return of(operation, workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId, args, null);

  }

  /**
   * Builds a call to be scheduled which carries a payload: the state its caller saw at
   * the moment it planned the operation. The bytes are stored beside the entry and the
   * entry names them, so what the caller passes here reaches the handler at dispatch
   * time unchanged.
   * <p>
   * The format is the caller's: VanillaBP carries bytes and reads none of them. A call
   * without a payload writes no row in the payload store and reads none at its
   * dispatch, so passing <code>null</code> costs exactly what it did before payloads
   * existed.
   *
   * @param operation The operation to execute
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow-aggregate ID in serialized form
   * @param adapterId The ID of the elected BPMS adapter or <code>null</code>
   * @param args Additional operation-specific arguments (may be <code>null</code>)
   * @param payload The bytes to carry, or <code>null</code> for a call without one
   * @return The call, ready to be handed to {@link PhaseTwoOutbox#schedule}
   * @throws IllegalArgumentException If the aggregate ID is longer than
   *         {@link #MAX_AGGREGATE_ID_LENGTH} characters, if the serialized args are
   *         longer than {@link #MAX_ARGS_LENGTH} characters, or if the payload is
   *         larger than {@link #MAX_PAYLOAD_SIZE} bytes (guiding message)
   */
  public static PhaseTwoCall of(
      final PhaseOperation operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId,
      final Map<String, String> args,
      final byte[] payload) {

    validateAggregateIdLength(operation, workflowModuleId, bpmnProcessId, workflowAggregateId);
    validateArgsLength(operation.name(), workflowModuleId, bpmnProcessId, args);
    validatePayloadSize(operation, workflowModuleId, bpmnProcessId, payload);
    // the key is derived from the call itself, so the call is built twice: once
    // to derive from, once carrying the result
    final var withoutKey = new PhaseTwoCall(
        operation
            .name(), workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId, args, Optional.empty(), null);
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
                .adapterId(), withPayloadReference(withoutKey.args(), payload), boundedKey, payload);

  }

  /**
   * Adds the reference of a payload to the arguments the entry persists. Called after
   * the idempotency key was derived, see {@link #ARG_PAYLOAD_REFERENCE}.
   *
   * @param args The arguments of the call
   * @param payload The payload or <code>null</code>
   * @return The arguments, unchanged where there is no payload
   */
  private static Map<String, String> withPayloadReference(
      final Map<String, String> args,
      final byte[] payload) {

    if (payload == null) {
      return args;
    }
    final var withReference = new java.util.LinkedHashMap<>(args);
    withReference.put(ARG_PAYLOAD_REFERENCE, java.util.UUID.randomUUID().toString());
    return withReference;

  }

  /**
   * Refuses a payload no store should be asked to hold. It sits next to the other two
   * guards and for the same reason: this is where the bytes are still at hand and where
   * the stack trace still points at the code which passed them.
   */
  private static void validatePayloadSize(
      final PhaseOperation operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final byte[] payload) {

    if ((payload == null) || (payload.length <= MAX_PAYLOAD_SIZE)) {
      return;
    }
    throw new IllegalArgumentException(
        """
            The payload of operation '%s' of BPMN process '%s' of workflow module '%s' is %d bytes \
            long, and a phase-two call carries at most %d bytes. The payload is what the caller saw \
            at the moment it planned the operation, so it is meant to be a state somebody reads, not \
            a file somebody transfers. Pass less of it - a selection of the fields the receiver \
            needs - or store the large part where it belongs and pass what points at it."""
            .formatted(
                operation.name(),
                bpmnProcessId,
                workflowModuleId,
                payload.length,
                MAX_PAYLOAD_SIZE));

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
   * Refuses args no store can hold, measured on the serialized form for the reason
   * given at {@link #MAX_ARGS_LENGTH}. Sits next to the aggregate-ID guard and for the
   * same reason: this is where the values are still at hand and where the stack trace
   * still points at the business code which passed them.
   */
  private static void validateArgsLength(
      final String operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final Map<String, String> args) {

    final var serialized = serializeArgs(args);
    if ((serialized == null) || (serialized.length() <= MAX_ARGS_LENGTH)) {
      return;
    }
    // the longest argument is the one worth naming: it is the value the caller has to
    // change, and with several args the total says nothing about which one it is
    final var longest = args
        .entrySet()
        .stream()
        .max(Comparator.comparingInt(entry -> entry.getValue() == null ? 0 : entry.getValue().length()))
        .orElseThrow();
    final var longestValue = longest.getValue() == null ? "" : longest.getValue();
    throw new IllegalArgumentException(
        """
            The arguments of operation '%s' of BPMN process '%s' of workflow module '%s' are %d \
            characters long once encoded for the store, which the phase-two outbox cannot store: \
            its ARGS column holds %d characters. The longest argument is '%s' with %d characters. \
            Such a value identifies something, a message name or a correlation id, and is not a \
            place to carry payload - what the workflow has to know belongs in the workflow \
            aggregate, which your application persists itself and VanillaBP hands to every \
            handler. The value begins with '%s'."""
            .formatted(
                operation,
                bpmnProcessId,
                workflowModuleId,
                serialized.length(),
                MAX_ARGS_LENGTH,
                longest.getKey(),
                longestValue.length(),
                longestValue.substring(0, Math.min(64, longestValue.length()))));

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

    return forDispatch(operation, workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId, args, null);

  }

  /**
   * Rebuilds a call from a persisted outbox entry, carrying the payload the store read
   * for the entry's {@link #ARG_PAYLOAD_REFERENCE}.
   *
   * @param operation The persisted operation NAME
   * @param workflowModuleId The ID of the workflow module the workflow belongs to
   * @param bpmnProcessId The BPMN process ID of the workflow
   * @param workflowAggregateId The workflow-aggregate ID in serialized form
   * @param adapterId The persisted adapter ID or <code>null</code>
   * @param args The persisted arguments (may be <code>null</code>)
   * @param payload The bytes read from the payload store, or <code>null</code> where
   *        the entry names none
   * @return The call, ready to be handed to the core's router
   */
  public static PhaseTwoCall forDispatch(
      final String operation,
      final String workflowModuleId,
      final String bpmnProcessId,
      final String workflowAggregateId,
      final String adapterId,
      final Map<String, String> args,
      final byte[] payload) {

    return new PhaseTwoCall(
        operation, workflowModuleId, bpmnProcessId, workflowAggregateId, adapterId, args, Optional
            .empty(), payload);

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
