package io.vanillabp.integration.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One operation of a workflow, defined once and in one place: its persisted name, the
 * rule deriving the idempotency key of a planned call, which BPMS executes it
 * ({@link Election}), whether every adapter has to be able to execute it, and how it names
 * itself in a message a developer reads.
 * <p>
 * What the operation DOES is not here, and deliberately so - it is two things in two
 * places. An adapter contributes a handler per operation
 * ({@code MigratableProcessService#phaseOperations()}), which is the pair of "ask" and
 * "act" for its own BPMS; the core routes to that handler
 * ({@link PhaseOperationRegistry} and {@link PhaseOperationDispatch}), and an extension
 * dispatching an operation of its own registers what it does instead.
 * <p>
 * <strong>Adding an operation</strong> means adding a constant below and a handler in
 * every adapter which can serve it. Nothing else: the outbox stores the name and the
 * arguments without knowing them, the router dispatches by name, and the core's
 * {@code MigrationProcessService} executes every operation through the same two
 * methods. That this stays true is decision 29 in the repository's DECISIONS.md.
 * <p>
 * <strong>Persisted contract:</strong> The operation's {@link #name()} as well as
 * the idempotency-key derivation rules below are persisted by
 * {@link PhaseTwoOutbox} implementations. Never rename an operation and never
 * change a derivation rule of an existing one - outbox entries scheduled by a
 * previous version of the application must still dispatch and deduplicate
 * correctly after an upgrade. Everything else here is behaviour of the running
 * application and may change with it.
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
 * something the BPMS activated and the delivering adapter names that activation (see
 * {@link RunningActivation}, which the core reads into
 * {@link PhaseTwoCall#ARG_ACTIVATION_ID} because this operation
 * {@link #carriesActivation()}). It is the ONLY key carrying one,
 * because it is the only one which has to deduplicate PER activation - the others
 * deduplicate across activations on purpose. WITHOUT a correlation id the key is {@link Optional#empty()} -
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
 * {@link #extensionOperation(String)}: their name has to be
 * namespaced (<code>my-extension:MY_OPERATION</code>) so an extension can never
 * collide with a core operation or with another extension.
 * <p>
 * Why an operation needs a key rule at all is decision 2 in the repository's DECISIONS.md: the
 * outbox dispatches at-least-once, so everything dispatched from it has to be repeatable.
 *
 * @param name The persisted name of the operation
 * @param idempotencyKey The rule deriving the idempotency key of a call of this
 *        operation
 * @param election Which BPMS executes the operation, see {@link Election}
 * @param requiredOfEveryAdapter Whether an adapter which serves a workflow module has
 *        to be able to execute this operation. An operation which is required is part
 *        of what makes an adapter usable at all and its absence fails the boot; an
 *        operation which is not may be missing because the BPMS has nothing like it
 *        (signals), and only an application actually asking for it learns so
 * @param carriesActivation Whether the activation the call was planned in travels with
 *        it ({@link PhaseTwoCall#ARG_ACTIVATION_ID}). Only correlation needs it, and
 *        the flag is here rather than in the core so a later operation which needs the
 *        same distinction says so in its own definition
 * @param wording How the operation names itself in messages, see {@link Wording}
 */
public record PhaseOperation(
                             String name,
                             IdempotencyKey idempotencyKey,
                             Election election,
                             boolean requiredOfEveryAdapter,
                             boolean carriesActivation,
                             Wording wording) {

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
   * Names what the operation does to what it does it to, in the words a developer
   * reads in a log line or an exception: &quot;correlating message 'approved'&quot;.
   * The core adds the workflow it happened to, so this phrase never repeats the
   * aggregate, the BPMN process or the workflow module.
   */
  @FunctionalInterface
  public interface Describe {

    /**
     * @param args The arguments of the call, see {@link PhaseTwoCall#args()}
     * @return The phrase, in the present participle and without a trailing period
     */
    String phrase(
        Map<String, String> args);

  }

  /**
   * What an operation contributes to the three messages the core builds about it. The
   * core owns the sentence around them, the operation owns what only it can say.
   *
   * @param describe What the operation does, see {@link Describe}
   * @param hintWhenUnknown Appended where no configured BPMS knows the workflow or the
   *        task the operation addresses - the place to name the neighbouring operation
   *        somebody probably meant. Empty where there is nothing to add
   * @param remedyWhenUnsupported Appended where the elected adapter cannot execute the
   *        operation at all, which only an operation with
   *        {@link PhaseOperation#requiredOfEveryAdapter()} <code>false</code> can run
   *        into. It says what to do instead, and is empty for a required operation
   */
  public record Wording(
                        Describe describe,
                        String hintWhenUnknown,
                        String remedyWhenUnsupported) {

    public Wording {
      Objects.requireNonNull(describe, "describe must not be null");
      hintWhenUnknown = Objects.requireNonNullElse(hintWhenUnknown, "");
      remedyWhenUnsupported = Objects.requireNonNullElse(remedyWhenUnsupported, "");
    }

  }

  /**
   * Phase one asks whether a workflow of this aggregate already exists, phase two
   * creates it - see the handler an adapter contributes for this operation.
   */
  public static final PhaseOperation START_WORKFLOW = named("START_WORKFLOW")
      .electedBy(Election.STARTS_THE_WORKFLOW)
      .requiredOfEveryAdapter()
      .idempotencyKey(
          call -> Optional
              .of(
                  // the operation is named as a literal rather than read from the call, so
                  // START_WORKFLOW_BY_MESSAGE deriving this key shares it instead of
                  // getting one of its own
                  "START_WORKFLOW|%s|%s|%s".formatted(
                      call.workflowModuleId(),
                      call.bpmnProcessId(),
                      call.workflowAggregateId())))
      .describedAs(args -> "starting the workflow")
      .build();

  /**
   * Completing an asynchronous task - a <code>&#64;WorkflowTask</code> method with a
   * <code>&#64;TaskId</code> parameter which returned without completing. The task ID
   * travels in {@link PhaseTwoCall#args()} under {@link PhaseTwoCall#ARG_TASK_ID}. No
   * adapter ID is persisted - the executing adapter is elected at dispatch time by
   * probing the prioritized adapters.
   */
  public static final PhaseOperation COMPLETE_TASK = named("COMPLETE_TASK")
      .electedBy(Election.HOLDS_THE_TASK)
      .requiredOfEveryAdapter()
      .idempotencyKey(PhaseOperation::taskKey)
      .describedAs(args -> "completing task '%s'".formatted(args.get(PhaseTwoCall.ARG_TASK_ID)))
      .build();

  /**
   * Canceling an asynchronous task by BPMN error. The task ID and the BPMN error code
   * travel in {@link PhaseTwoCall#args()} under {@link PhaseTwoCall#ARG_TASK_ID} /
   * {@link PhaseTwoCall#ARG_BPMN_ERROR_CODE}; only the task ID is part of the
   * idempotency key.
   */
  public static final PhaseOperation CANCEL_TASK = named("CANCEL_TASK")
      .electedBy(Election.HOLDS_THE_TASK)
      .requiredOfEveryAdapter()
      .idempotencyKey(PhaseOperation::taskKey)
      .describedAs(
          args -> "canceling task '%s' by BPMN error '%s'".formatted(
              args.get(PhaseTwoCall.ARG_TASK_ID),
              args.get(PhaseTwoCall.ARG_BPMN_ERROR_CODE)))
      .build();

  /**
   * Completing a USER task. Same shape as {@link #COMPLETE_TASK} (task ID in
   * {@link PhaseTwoCall#ARG_TASK_ID}, no adapter ID persisted), but a question of its
   * own: user-task IDs live in a different namespace than service-task IDs.
   */
  public static final PhaseOperation COMPLETE_USER_TASK = named("COMPLETE_USER_TASK")
      .electedBy(Election.HOLDS_THE_USER_TASK)
      .requiredOfEveryAdapter()
      .idempotencyKey(PhaseOperation::taskKey)
      .describedAs(args -> "completing user task '%s'".formatted(args.get(PhaseTwoCall.ARG_TASK_ID)))
      .build();

  /**
   * Canceling a USER task by BPMN error. Same shape as {@link #CANCEL_TASK}.
   */
  public static final PhaseOperation CANCEL_USER_TASK = named("CANCEL_USER_TASK")
      .electedBy(Election.HOLDS_THE_USER_TASK)
      .requiredOfEveryAdapter()
      .idempotencyKey(PhaseOperation::taskKey)
      .describedAs(
          args -> "canceling user task '%s' by BPMN error '%s'".formatted(
              args.get(PhaseTwoCall.ARG_TASK_ID),
              args.get(PhaseTwoCall.ARG_BPMN_ERROR_CODE)))
      .build();

  /**
   * Correlating a message with the workflow of an aggregate. The message name and the
   * optional correlation id travel in {@link PhaseTwoCall#args()} under
   * {@link PhaseTwoCall#ARG_MESSAGE_NAME} / {@link PhaseTwoCall#ARG_CORRELATION_ID}.
   * No adapter ID is persisted - the executing adapter is elected at dispatch time by
   * probing the prioritized adapters.
   */
  public static final PhaseOperation CORRELATE_MESSAGE = named("CORRELATE_MESSAGE")
      .electedBy(Election.HOLDS_THE_WORKFLOW)
      .requiredOfEveryAdapter()
      .carryingTheActivation()
      .idempotencyKey(PhaseOperation::correlationKey)
      .describedAs(PhaseOperation::describeCorrelation)
      .hintingWhenUnknown("To START a workflow by a message use startWorkflowByMessage instead.")
      .build();

  /**
   * Starting a workflow by a message start event. Start semantics apply: the adapter
   * elected in phase one IS persisted with the entry and the idempotency key equals
   * {@link #START_WORKFLOW}'s (one workflow per aggregate). The message name travels
   * in {@link PhaseTwoCall#args()}.
   */
  public static final PhaseOperation START_WORKFLOW_BY_MESSAGE = named("START_WORKFLOW_BY_MESSAGE")
      .electedBy(Election.STARTS_THE_WORKFLOW)
      .requiredOfEveryAdapter()
      .idempotencyKey(call -> START_WORKFLOW.idempotencyKey().derive(call))
      .describedAs(
          args -> "starting the workflow by message '%s'".formatted(args.get(PhaseTwoCall.ARG_MESSAGE_NAME)))
      .build();

  /**
   * Broadcasting a BPMN signal. The signal name travels in {@link PhaseTwoCall#args()}
   * under {@link PhaseTwoCall#ARG_SIGNAL_NAME}, the broadcasting adapter IS persisted
   * with the entry (a broadcast goes to every BPMS the workflow module was deployed to,
   * so each of them gets its own entry). NO idempotency key: a signal has nothing to
   * deduplicate by, and the same signal may legitimately be broadcast again and again.
   * <p>
   * Not required of every adapter: a BPMS without signals says so through the message
   * below instead of swallowing a broadcast.
   */
  public static final PhaseOperation SEND_SIGNAL = named("SEND_SIGNAL")
      .electedBy(Election.EVERY_DEPLOYED_BPMS)
      .describedAs(args -> "broadcasting signal '%s'".formatted(args.get(PhaseTwoCall.ARG_SIGNAL_NAME)))
      .remedyWhenUnsupported(
          "Remove the adapter from the prioritized adapters of this workflow module, or replace the "
              + "signal by a message correlated to the workflow which waits for it.")
      .build();

  /**
   * Pushing the values shared with the BPMS (<code>&#64;SyncWithBPMS</code>) of a
   * changed workflow-aggregate. An optional task ID travels in
   * {@link PhaseTwoCall#args()} under {@link PhaseTwoCall#ARG_TASK_ID}; without it the
   * values are written at the workflow's global scope. No adapter ID is persisted - the
   * executing adapter is elected at dispatch time by probing.
   * <p>
   * NO idempotency key, and that is not a shortcut: the values are read from the
   * aggregate when the entry is DISPATCHED, so a redelivered entry writes the
   * then-current state. Deduplicating could only drop a push, never save one.
   * <p>
   * Not required of every adapter: a BPMS which cannot update a running instance says
   * so instead of pretending the push happened.
   */
  public static final PhaseOperation AGGREGATE_CHANGED = named("AGGREGATE_CHANGED")
      .electedBy(Election.HOLDS_THE_WORKFLOW)
      .describedAs(args -> "pushing the changed aggregate")
      .remedyWhenUnsupported(
          "Remove the adapter from the prioritized adapters of this workflow module, or model a task "
              + "the workflow waits at - completing it pushes the aggregate as well.")
      .build();

  /**
   * All operations owned by the VanillaBP core. Their names are reserved: an
   * extension registering one of them is rejected by
   * {@link PhaseOperationRegistry#register(PhaseOperation, PhaseOperationDispatch)}.
   */
  public static final List<PhaseOperation> CORE_OPERATIONS = List
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

  public PhaseOperation {
    Objects.requireNonNull(name, "name must not be null");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey must not be null");
    Objects.requireNonNull(election, "election must not be null");
    Objects.requireNonNull(wording, "wording must not be null");
    if (name.isBlank()) {
      throw new IllegalArgumentException("The name of a phase operation must not be blank!");
    }
  }

  /**
   * What this operation does to what it does it to, in the words a message uses.
   *
   * @param args The arguments of the call, see {@link PhaseTwoCall#args()}
   * @return The phrase, e.g. &quot;correlating message 'approved'&quot;
   */
  public String describe(
      final Map<String, String> args) {

    return wording.describe().phrase(args == null ? Map.of() : args);

  }

  /**
   * Starts building an operation contributed by an extension. The name has to carry the
   * extension's namespace (<code>my-extension:MY_OPERATION</code>) - see
   * {@link #NAMESPACE_SEPARATOR} - which is checked by {@link Builder#build()}.
   * <p>
   * Without further calls the operation is dispatched by the extension itself
   * ({@link Election#OWN_DISPATCH}) and never deduplicated. An extension whose
   * operation addresses a workflow like a core operation does picks the election which
   * says so ({@link Builder#electedBy(Election)}) and contributes a handler per adapter
   * instead of a dispatch of its own.
   *
   * @param name The namespaced name of the operation - persisted, so treat it as a
   *        contract
   * @return The builder
   */
  public static Builder extensionOperation(
      final String name) {

    return new Builder(name, true);

  }

  /**
   * @param name The name to look up
   * @return The core operation of that name, if there is one
   */
  public static Optional<PhaseOperation> coreOperation(
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
        .map(PhaseOperation::name)
        .toList();

  }

  static void validateNamespaced(
      final PhaseOperation operation) {

    final var separator = operation.name().indexOf(NAMESPACE_SEPARATOR);
    if ((separator < 1) || (separator == (operation.name().length() - 1))) {
      throw new IllegalArgumentException(
          """
              The phase operation '%s' is not namespaced! Operations contributed by an extension \
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
   * Collects what an operation is made of. Everything but the name has a default, so a
   * definition names only what distinguishes it: an operation which is never
   * deduplicated says nothing about a key, and one an extension dispatches itself says
   * nothing about an election.
   */
  public static final class Builder {

    private final String name;

    private final boolean namespaced;

    private IdempotencyKey idempotencyKey = call -> Optional.empty();

    private Election election = Election.OWN_DISPATCH;

    private boolean requiredOfEveryAdapter;

    private boolean carriesActivation;

    private Describe describe;

    private String hintWhenUnknown = "";

    private String remedyWhenUnsupported = "";

    private Builder(
        final String name,
        final boolean namespaced) {

      this.name = name;
      this.namespaced = namespaced;

    }

    /**
     * @param idempotencyKey The rule deriving the idempotency key of a call, or
     *        <code>call -&gt; Optional.empty()</code> (the default) for operations
     *        which must not be deduplicated. Name the operation in the key - the core
     *        operations start their keys with it - unless two of your operations are
     *        meant to deduplicate against each other
     * @return This builder
     */
    public Builder idempotencyKey(
        final IdempotencyKey idempotencyKey) {

      this.idempotencyKey = idempotencyKey;
      return this;

    }

    /**
     * @param election Which BPMS executes the operation, {@link Election#OWN_DISPATCH}
     *        by default
     * @return This builder
     */
    public Builder electedBy(
        final Election election) {

      this.election = election;
      return this;

    }

    /**
     * States that an adapter which serves a workflow module has to be able to execute
     * this operation, so an adapter missing a handler for it fails the boot rather than
     * the first application call.
     *
     * @return This builder
     */
    public Builder requiredOfEveryAdapter() {

      this.requiredOfEveryAdapter = true;
      return this;

    }

    /**
     * States that the activation the call was planned in travels with it
     * ({@link PhaseTwoCall#ARG_ACTIVATION_ID}) - needed by an operation whose calls
     * have to be told apart per activation of a BPMN element, and by an adapter whose
     * BPMS deduplicates in a net of its own.
     *
     * @return This builder
     */
    public Builder carryingTheActivation() {

      this.carriesActivation = true;
      return this;

    }

    /**
     * @param describe What the operation does, see {@link Describe}
     * @return This builder
     */
    public Builder describedAs(
        final Describe describe) {

      this.describe = describe;
      return this;

    }

    /**
     * @param hintWhenUnknown What to add where no configured BPMS knows what the
     *        operation addresses, see {@link Wording#hintWhenUnknown()}
     * @return This builder
     */
    public Builder hintingWhenUnknown(
        final String hintWhenUnknown) {

      this.hintWhenUnknown = hintWhenUnknown;
      return this;

    }

    /**
     * @param remedyWhenUnsupported What to add where the elected adapter cannot execute
     *        the operation, see {@link Wording#remedyWhenUnsupported()}
     * @return This builder
     */
    public Builder remedyWhenUnsupported(
        final String remedyWhenUnsupported) {

      this.remedyWhenUnsupported = remedyWhenUnsupported;
      return this;

    }

    /**
     * @return The operation
     * @throws IllegalArgumentException If an extension's operation is not namespaced
     *         (guiding message)
     */
    public PhaseOperation build() {

      final var operation = new PhaseOperation(
          name, idempotencyKey, election, requiredOfEveryAdapter, carriesActivation, new Wording(
              describe == null
                  ? args -> "executing '%s'".formatted(name)
                  : describe, hintWhenUnknown, remedyWhenUnsupported));
      if (namespaced) {
        validateNamespaced(operation);
      }
      return operation;

    }

  }

  private static Builder named(
      final String name) {

    return new Builder(name, false);

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

  private static Optional<String> correlationKey(
      final PhaseTwoCall call) {

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
    // which planned the correlation is what tells them apart. It is read from the
    // call rather than from the thread, which keeps this derivation a pure function
    // of what a store persists and lets the same value reach the adapter at dispatch
    // time. Outside an invocation there is none, and the key is then exactly the one
    // it always was
    final var activation = call.args().get(PhaseTwoCall.ARG_ACTIVATION_ID);
    return Optional
        .of(
            activation == null
                ? key
                : "%s|%s".formatted(key, activation));

  }

  private static String describeCorrelation(
      final Map<String, String> args) {

    final var correlationId = args.get(PhaseTwoCall.ARG_CORRELATION_ID);
    final var messageName = args.get(PhaseTwoCall.ARG_MESSAGE_NAME);
    return correlationId == null
        ? "correlating message '%s'".formatted(messageName)
        : "correlating message '%s' (correlation id '%s')".formatted(messageName, correlationId);

  }

}
