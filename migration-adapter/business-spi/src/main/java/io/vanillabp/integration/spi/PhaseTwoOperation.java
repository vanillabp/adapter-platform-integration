package io.vanillabp.integration.spi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The operation a {@link PhaseTwoCall} executes after the local transaction was
 * committed: a persisted NAME plus the rule deriving the call's idempotency key.
 * What an operation actually DOES at dispatch time is registered separately (see
 * {@link PhaseTwoOperationRegistry} and {@link PhaseTwoOperationDispatch}) - the
 * core registers its operations, extensions register theirs.
 * <p>
 * <strong>Persisted contract:</strong> The operation's {@link #name()} as well as
 * the idempotency-key derivation rules below are persisted by
 * {@link PhaseTwoOutbox} implementations. Never rename an operation and never
 * change a derivation rule of an existing one - outbox entries scheduled by a
 * previous version of the application must still dispatch and deduplicate
 * correctly after an upgrade.
 * <p>
 * <strong>Idempotency-key derivation rules of the core operations</strong> (parts
 * joined by <code>|</code>). Every key starts with the name of the operation it
 * deduplicates, because two operations of one task are two different pieces of work:
 * <ul>
 * <li>{@link #START_WORKFLOW}:
 * <code>START_WORKFLOW|workflowModuleId|bpmnProcessId|workflowAggregateId</code> - a
 * workflow is started at most once per aggregate, and no activation is appended: two
 * activations asking for the same workflow ask for the same one.</li>
 * <li>{@link #COMPLETE_TASK} / {@link #CANCEL_TASK}:
 * <code>&lt;operation&gt;|workflowModuleId|bpmnProcessId|workflowAggregateId|taskId</code>
 * - the same task is completed (or canceled) at most once, but multiple tasks of the
 * same workflow may be completed, and no activation is appended: a task ID already names
 * one activation of one element. The BPMN error code of a cancellation is NOT part of
 * the key (it is carried in {@link PhaseTwoCall#args()}).</li>
 * <li>{@link #COMPLETE_USER_TASK} / {@link #CANCEL_USER_TASK}: like their
 * asynchronous-task counterparts, and distinct from them by the operation name.</li>
 * <li>{@link #CORRELATE_MESSAGE}: WITH a correlation id the key is
 * <code>CORRELATE_MESSAGE|workflowModuleId|bpmnProcessId|workflowAggregateId|messageName|correlationId</code>,
 * followed by <code>|activationId</code> where the correlation was planned inside
 * something the BPMS activated and the delivering adapter names that activation
 * ({@link RunningActivation}). It is the ONLY key carrying one, because it is the only
 * one which has to deduplicate PER activation - the others deduplicate across
 * activations on purpose. WITHOUT a correlation id the key is {@link Optional#empty()} -
 * no deduplication is possible because the same message may legitimately be correlated
 * multiple times over an instance's lifetime (an at-least-once dispatch may then
 * double-correlate; see the adapters' documentation).</li>
 * <li>{@link #SEND_SIGNAL}: {@link Optional#empty()} - a broadcast signal has no
 * key to deduplicate by.</li>
 * <li>{@link #START_WORKFLOW_BY_MESSAGE}: exactly {@link #START_WORKFLOW}'s key,
 * <code>START_WORKFLOW|...</code> and not the operation's own name - a workflow is
 * started at most once per aggregate, regardless of which of the two started it. This
 * is the one place where two operations deliberately share a key.</li>
 * </ul>
 * <p>
 * A key deduplicates a PLANNED operation, not one which already reached the BPMS: what
 * a store looks it up against is the entries still waiting for their dispatch. See
 * decision 22 in the repository's DECISIONS.md, and
 * {@link PhaseTwoOutbox#schedule(PhaseTwoCall)} for what a caller sees.
 * <p>
 * <strong>Operations of extensions</strong> are built by
 * {@link #extensionOperation(String, IdempotencyKey)}: their name has to be
 * namespaced (<code>my-extension:MY_OPERATION</code>) so an extension can never
 * collide with a core operation or with another extension.
 * <p>
 * Why an operation needs a key rule at all is decision 2 in the repository's DECISIONS.md: the
 * outbox dispatches at-least-once, so everything dispatched from it has to be repeatable.
 * @param name The persisted name of the operation
 * @param idempotencyKey The rule deriving the idempotency key of a call of this
 *        operation
 */
public record PhaseTwoOperation(
                                String name,
                                IdempotencyKey idempotencyKey) {

  /**
   * Separates an extension's namespace from the operation's own name (e.g.
   * <code>businesscockpit:PUBLISH_USER_TASK_EVENT</code>). Core operations never
   * contain it, which is what makes collisions impossible.
   */
  public static final String NAMESPACE_SEPARATOR = ":";

  /**
   * Derives the idempotency key of a {@link PhaseTwoCall} - the rule is part of
   * the persisted contract of the operation it belongs to.
   */
  @FunctionalInterface
  public interface IdempotencyKey {

    /**
     * @param call The call to derive the key for
     * @return The idempotency key or {@link Optional#empty()} if calls of this
     *         operation must not be deduplicated
     */
    Optional<String> derive(
        PhaseTwoCall call);

  }

  /**
   * Phase two of starting a workflow - see
   * {@code MigratableProcessService#startWorkflowPhaseTwo}.
   */
  public static final PhaseTwoOperation START_WORKFLOW = new PhaseTwoOperation(
      "START_WORKFLOW", call -> Optional
          .of(
              // the operation is named as a literal rather than read from the call, so
              // START_WORKFLOW_BY_MESSAGE deriving this key shares it instead of
              // getting one of its own
              "START_WORKFLOW|%s|%s|%s".formatted(
                  call.workflowModuleId(),
                  call.bpmnProcessId(),
                  call.workflowAggregateId())));

  /**
   * Phase two of completing an asynchronous task - see
   * {@code MigratableProcessService#completeTaskPhaseTwo}. The task ID travels in
   * {@link PhaseTwoCall#args()} under {@link PhaseTwoCall#ARG_TASK_ID}. No adapter
   * ID is persisted - the executing adapter is elected at dispatch time by probing
   * the prioritized adapters.
   */
  public static final PhaseTwoOperation COMPLETE_TASK = new PhaseTwoOperation(
      "COMPLETE_TASK", PhaseTwoOperation::taskKey);

  /**
   * Phase two of canceling an asynchronous task by BPMN error - see
   * {@code MigratableProcessService#cancelTaskPhaseTwo}. The task ID and the BPMN
   * error code travel in {@link PhaseTwoCall#args()} under
   * {@link PhaseTwoCall#ARG_TASK_ID} / {@link PhaseTwoCall#ARG_BPMN_ERROR_CODE};
   * only the task ID is part of the idempotency key.
   */
  public static final PhaseTwoOperation CANCEL_TASK = new PhaseTwoOperation(
      "CANCEL_TASK", PhaseTwoOperation::taskKey);

  /**
   * Phase two of completing a USER task - see
   * {@code MigratableProcessService#completeUserTaskPhaseTwo}. Same shape as
   * {@link #COMPLETE_TASK} (task ID in {@link PhaseTwoCall#ARG_TASK_ID}, no
   * adapter ID persisted).
   */
  public static final PhaseTwoOperation COMPLETE_USER_TASK = new PhaseTwoOperation(
      "COMPLETE_USER_TASK", PhaseTwoOperation::taskKey);

  /**
   * Phase two of canceling a USER task by BPMN error - see
   * {@code MigratableProcessService#cancelUserTaskPhaseTwo}. Same shape as
   * {@link #CANCEL_TASK}.
   */
  public static final PhaseTwoOperation CANCEL_USER_TASK = new PhaseTwoOperation(
      "CANCEL_USER_TASK", PhaseTwoOperation::taskKey);

  /**
   * Phase two of correlating a message - see
   * {@code MigratableProcessService#correlateMessagePhaseTwo}. The message name
   * (and optional correlation id) travel in {@link PhaseTwoCall#args()} under
   * {@link PhaseTwoCall#ARG_MESSAGE_NAME} / {@link PhaseTwoCall#ARG_CORRELATION_ID}.
   * No adapter ID is persisted - the executing adapter is elected at dispatch time
   * by probing the prioritized adapters.
   */
  public static final PhaseTwoOperation CORRELATE_MESSAGE = new PhaseTwoOperation(
      "CORRELATE_MESSAGE", call -> {
        final var correlationId = call.args().get(PhaseTwoCall.ARG_CORRELATION_ID);
        if (correlationId == null) {
          // the same message may legitimately be correlated multiple times - no
          // deduplication possible (documented at-least-once residual). An activation
          // would not help here: it would start deduplicating what is deliberately not
          // deduplicated
          return Optional.empty();
        }
        final var key = "%s|%s|%s|%s|%s|%s".formatted(
            call.operation(),
            call.workflowModuleId(),
            call.bpmnProcessId(),
            call.workflowAggregateId(),
            call.args().get(PhaseTwoCall.ARG_MESSAGE_NAME),
            correlationId);
        // Multi-instance siblings of one aggregate agree in every part above - a called
        // process is a secondary workflow of the SAME aggregate - so the activation
        // which planned the correlation is what tells them apart. Outside an invocation
        // there is none, and the key is then exactly the one it always was
        final var activation = RunningActivation.current();
        return Optional
            .of(
                activation == null
                    ? key
                    : "%s|%s".formatted(key, activation));
      });

  /**
   * Phase two of starting a workflow BY MESSAGE - see
   * {@code MigratableProcessService#startWorkflowByMessagePhaseTwo}. Start
   * semantics apply: the adapter elected in phase one IS persisted with the entry
   * and the idempotency key equals {@link #START_WORKFLOW}'s (one workflow per
   * aggregate). The message name travels in {@link PhaseTwoCall#args()}.
   */
  public static final PhaseTwoOperation START_WORKFLOW_BY_MESSAGE = new PhaseTwoOperation(
      "START_WORKFLOW_BY_MESSAGE", call -> START_WORKFLOW.idempotencyKey().derive(call));

  /**
   * Phase two of broadcasting a BPMN signal - see
   * {@code MigratableProcessService#sendSignalPhaseTwo}. The signal name travels in
   * {@link PhaseTwoCall#args()} under {@link PhaseTwoCall#ARG_SIGNAL_NAME}, the
   * broadcasting adapter IS persisted with the entry (a broadcast goes to every
   * BPMS the workflow module was deployed to, so each of them gets its own entry).
   * NO idempotency key: a signal has nothing to deduplicate by, and the same signal
   * may legitimately be broadcast again and again.
   */
  public static final PhaseTwoOperation SEND_SIGNAL = new PhaseTwoOperation(
      "SEND_SIGNAL", call -> Optional.empty());

  /**
   * Phase two of pushing a changed workflow-aggregate to the BPMS - see
   * {@code MigratableProcessService#aggregateChangedPhaseTwo}. An optional task ID
   * travels in {@link PhaseTwoCall#args()} under {@link PhaseTwoCall#ARG_TASK_ID};
   * without it the values are written at the workflow's global scope. No adapter ID
   * is persisted - the executing adapter is elected at dispatch time by probing.
   * <p>
   * NO idempotency key, and that is not a shortcut: the values are read from the
   * aggregate when the entry is DISPATCHED, so a redelivered entry writes the
   * then-current state. Deduplicating could only drop a push, never save one.
   */
  public static final PhaseTwoOperation AGGREGATE_CHANGED = new PhaseTwoOperation(
      "AGGREGATE_CHANGED", call -> Optional.empty());

  /**
   * All operations owned by the VanillaBP core. Their names are reserved: an
   * extension registering one of them is rejected by
   * {@link PhaseTwoOperationRegistry#register(PhaseTwoOperation, PhaseTwoOperationDispatch)}.
   */
  public static final List<PhaseTwoOperation> CORE_OPERATIONS = List
      .of(
          START_WORKFLOW,
          COMPLETE_TASK,
          CANCEL_TASK,
          COMPLETE_USER_TASK,
          CANCEL_USER_TASK,
          CORRELATE_MESSAGE,
          START_WORKFLOW_BY_MESSAGE,
          SEND_SIGNAL,
          AGGREGATE_CHANGED);

  public PhaseTwoOperation {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("The name of a phase-two operation must not be blank!");
    }
  }

  /**
   * Builds an operation contributed by an extension. The name has to carry the
   * extension's namespace (<code>my-extension:MY_OPERATION</code>) - see
   * {@link #NAMESPACE_SEPARATOR}.
   *
   * @param name The namespaced name of the operation - persisted, so treat it as
   *        a contract
   * @param idempotencyKey The rule deriving the idempotency key of a call, or
   *        <code>call -&gt; Optional.empty()</code> for operations which must not
   *        be deduplicated. Name the operation in the key (the core operations start
   *        their keys with it) unless two of your operations are meant to deduplicate
   *        against each other
   * @return The operation, to be registered together with its dispatch
   * @throws IllegalArgumentException If the name is not namespaced (guiding
   *         message)
   */
  public static PhaseTwoOperation extensionOperation(
      final String name,
      final IdempotencyKey idempotencyKey) {

    final var operation = new PhaseTwoOperation(name, idempotencyKey);
    validateNamespaced(operation);
    return operation;

  }

  /**
   * @param name The name to look up
   * @return The core operation of that name, if there is one
   */
  public static Optional<PhaseTwoOperation> coreOperation(
      final String name) {

    return CORE_OPERATIONS
        .stream()
        .filter(operation -> operation.name().equals(name))
        .findFirst();

  }

  /**
   * The names of all core operations, in the order of {@link #CORE_OPERATIONS} -
   * used for guiding messages.
   *
   * @return The core operations' names
   */
  public static List<String> coreOperationNames() {

    return CORE_OPERATIONS
        .stream()
        .map(PhaseTwoOperation::name)
        .toList();

  }

  static void validateNamespaced(
      final PhaseTwoOperation operation) {

    final var separator = operation.name().indexOf(NAMESPACE_SEPARATOR);
    if ((separator < 1) || (separator == (operation.name().length() - 1))) {
      throw new IllegalArgumentException(
          """
              The phase-two operation '%s' is not namespaced! Operations contributed by an extension \
              have to be named '<extension>%s<operation>' (e.g. 'my-extension%sMY_OPERATION') so they \
              can never collide with VanillaBP's core operations (%s) or with another extension's \
              operations. The name is persisted in the outbox store - choose it once and keep it."""
              .formatted(
                  operation.name(),
                  NAMESPACE_SEPARATOR,
                  NAMESPACE_SEPARATOR,
                  String.join(", ", coreOperationNames())));
    }

  }

  /**
   * The key of an operation on ONE task. The operation is part of it because
   * completing and canceling one task are two operations, and so are the
   * asynchronous and the user-task flavour of each - one task id used to yield one
   * key for all four of them.
   */
  private static Optional<String> taskKey(
      final PhaseTwoCall call) {

    return Optional
        .of(
            "%s|%s|%s|%s|%s".formatted(
                call.operation(),
                call.workflowModuleId(),
                call.bpmnProcessId(),
                call.workflowAggregateId(),
                call.args().get(PhaseTwoCall.ARG_TASK_ID)));

  }

}
