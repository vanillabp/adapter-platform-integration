package io.vanillabp.integration.spi;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One operation of a workflow, defined once and in one place. It holds the persisted name,
 * the rule which derives the idempotency key of a planned call, which BPMS executes it
 * ({@link Election}), whether every adapter has to be able to execute it, and how it names
 * itself in a message a developer reads.
 * <p>
 * What the operation DOES is not here, on purpose. That is two things in two places. An
 * adapter contributes a handler per operation ({@code MigratableProcessService#phaseOperations()}),
 * which is the pair of "ask" and "act" for its own BPMS. The core routes to that handler
 * ({@link PhaseOperationRegistry} and {@link PhaseOperationDispatch}). An extension which
 * dispatches an operation of its own registers what it does instead.
 * <p>
 * <strong>Adding an operation</strong> means adding a constant below, and a handler in
 * every adapter which can serve it. Nothing else. The outbox stores the name and the
 * arguments without knowing them. The router dispatches by name. And the core's
 * {@code MigrationProcessService} executes every operation through the same two
 * methods. That this stays true is decision 29 in the repository's DECISIONS.md.
 * <p>
 * <strong>Persisted contract:</strong> {@link PhaseTwoOutbox} implementations persist the
 * {@link #name()} of the operation and the rules below which derive the idempotency key.
 * Never rename an operation, and never change a rule of an existing one. Outbox entries
 * scheduled by an earlier version of the application must still dispatch and deduplicate
 * correctly after an upgrade. Everything else here is behaviour of the running
 * application, and it may change with it.
 * <p>
 * <strong>How the core operations derive their idempotency keys</strong> (parts
 * joined by <code>|</code>). Every key starts with the name of the operation it
 * deduplicates, because two operations of one task are two different pieces of work:
 * <ul>
 * <li>{@link #START_WORKFLOW}:
 * <code>START_WORKFLOW|workflowModuleId|bpmnProcessId|workflowAggregateId</code>. A
 * workflow is started at most once per aggregate. No activation is appended: two
 * activations which ask for the same workflow ask for the same one.</li>
 * <li>{@link #COMPLETE_TASK} / {@link #CANCEL_TASK}:
 * <code>&lt;operation&gt;|workflowModuleId|bpmnProcessId|workflowAggregateId|taskId</code>.
 * The same task is completed (or canceled) at most once, but several tasks of the
 * same workflow may be completed. No activation is appended, because a task ID already
 * names one activation of one element. The BPMN error code of a cancellation is NOT part
 * of the key (it is carried in {@link PhaseTwoCall#args()}).</li>
 * <li>{@link #COMPLETE_USER_TASK} / {@link #CANCEL_USER_TASK}: like their counterparts for
 * asynchronous tasks, and told apart from them by the operation name.</li>
 * <li>{@link #CORRELATE_MESSAGE}: WITH a correlation id the key is
 * <code>CORRELATE_MESSAGE|workflowModuleId|bpmnProcessId|workflowAggregateId|messageName|correlationId</code>.
 * It is followed by <code>|activationId</code> if the correlation was planned inside
 * something the BPMS activated, and the delivering adapter names that activation (see
 * {@link RunningActivation}. The core reads it into
 * {@link PhaseTwoCall#ARG_ACTIVATION_ID}, because this operation
 * {@link #carriesActivation()}). It is the ONLY key which carries one,
 * because it is the only one which has to deduplicate PER activation. The others
 * deduplicate across activations on purpose. WITHOUT a correlation id the key is
 * {@link Optional#empty()}. Then no deduplication is possible, because the same message may
 * rightly be correlated several times while an instance lives. A dispatch which happens at
 * least once may then correlate twice; see the documentation of the adapters.</li>
 * <li>{@link #SEND_SIGNAL}: {@link Optional#empty()}. A broadcast signal has no
 * key to deduplicate by.</li>
 * <li>{@link #START_WORKFLOW_BY_MESSAGE}: exactly the key of {@link #START_WORKFLOW},
 * <code>START_WORKFLOW|...</code>, and not the own name of the operation. A workflow is
 * started at most once per aggregate, whichever of the two started it. This is the one
 * place where two operations share a key on purpose.</li>
 * </ul>
 * <p>
 * A key deduplicates a PLANNED operation, not one which already reached the BPMS. A store
 * looks it up against the entries which still wait for their dispatch. See
 * decision 22 in the repository's DECISIONS.md, and
 * {@link PhaseTwoOutbox#schedule(PhaseTwoCall)} for what a caller sees.
 * <p>
 * <strong>Operations of extensions</strong> are built by
 * {@link #extensionOperation(String)}. Their name has to carry a namespace
 * (<code>my-extension:MY_OPERATION</code>), so an extension can never
 * collide with a core operation or with another extension.
 * <p>
 * Why an operation needs a key rule at all is decision 2 in the repository's DECISIONS.md. The
 * outbox dispatches at least once, so everything dispatched from it has to be repeatable.
 *
 * @param name The persisted name of the operation
 * @param idempotencyKey The rule deriving the idempotency key of a call of this
 *        operation
 * @param election Which BPMS executes the operation, see {@link Election}
 * @param requiredOfEveryAdapter Whether an adapter which serves a workflow module has
 *        to be able to execute this operation. A required operation is part of what
 *        makes an adapter usable at all, and if it is missing, the boot fails. An
 *        operation which is not required may be missing because the BPMS has nothing like
 *        it (signals). Only an application which really asks for it learns that
 * @param carriesActivation Whether the activation the call was planned in travels with
 *        it ({@link PhaseTwoCall#ARG_ACTIVATION_ID}). Only correlation needs it. The
 *        flag is here and not in the core, so a later operation which needs the same
 *        distinction says so in its own definition
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
   * Separates the namespace of an extension from the own name of the operation (e.g.
   * <code>businesscockpit:PUBLISH_USER_TASK_EVENT</code>). Core operations never
   * contain it, and that makes collisions impossible.
   */
  public static final String NAMESPACE_SEPARATOR = ":";

  /**
   * Derives the idempotency key of a {@link PhaseTwoCall}. The rule is part of the
   * persisted contract of the operation it belongs to.
   */
  @FunctionalInterface
  public interface IdempotencyKey {

    /**
     * Derives the key of one call. Read only what the call carries. The rule belongs to the
     * persisted contract of its operation, so a call scheduled by an older version of the
     * application has to give the same key today.
     *
     * @param call The call to derive the key for
     * @return The idempotency key or {@link Optional#empty()} if calls of this
     *         operation must not be deduplicated
     */
    Optional<String> derive(
        PhaseTwoCall call);

  }

  /**
   * Names what the operation does, and to what, in the words a developer reads in a log
   * line or an exception: &quot;correlating message 'approved'&quot;. The core adds the
   * workflow it happened to. So this phrase never repeats the aggregate, the BPMN process
   * or the workflow module.
   */
  @FunctionalInterface
  public interface Describe {

    /**
     * Names what one call does, from the arguments it carries. An argument which is not
     * there reads as <code>null</code> in the phrase. So build the phrase from what the
     * operation always carries.
     *
     * @param args The arguments of the call, see {@link PhaseTwoCall#args()}
     * @return The phrase, in the present participle and without a trailing period
     */
    String phrase(
        Map<String, String> args);

  }

  /**
   * What an operation adds to the three messages the core builds about it. The core owns
   * the sentence around them. The operation owns what only it can say.
   *
   * @param describe What the operation does, see {@link Describe}
   * @param hintWhenUnknown Added if no configured BPMS knows the workflow or the task the
   *        operation addresses. This is the place to name the similar operation somebody
   *        probably meant. Empty if there is nothing to add
   * @param remedyWhenUnsupported Added if the elected adapter cannot execute the operation
   *        at all. Only an operation with {@link PhaseOperation#requiredOfEveryAdapter()}
   *        <code>false</code> can run into this. It says what to do instead, and it is
   *        empty for a required operation
   */
  public record Wording(
                        Describe describe,
                        String hintWhenUnknown,
                        String remedyWhenUnsupported) {

    /**
     * Takes a wording without a hint or a remedy. Both texts are optional and become
     * empty strings, so the core can append them without checking whether there is one.
     */
    public Wording {
      Objects.requireNonNull(describe, "describe must not be null");
      hintWhenUnknown = Objects.requireNonNullElse(hintWhenUnknown, "");
      remedyWhenUnsupported = Objects.requireNonNullElse(remedyWhenUnsupported, "");
    }

  }

  /**
   * Phase one asks whether a workflow of this aggregate already exists. Phase two
   * creates it. See the handler an adapter contributes for this operation.
   */
  public static final PhaseOperation START_WORKFLOW = named("START_WORKFLOW")
      .electedBy(Election.STARTS_THE_WORKFLOW)
      .requiredOfEveryAdapter()
      .idempotencyKey(
          call -> Optional
              .of(
                  // the operation is named as a literal and not read from the call. So
                  // START_WORKFLOW_BY_MESSAGE, which derives this key, shares it and does
                  // not get one of its own
                  "START_WORKFLOW|%s|%s|%s".formatted(
                      call.workflowModuleId(),
                      call.bpmnProcessId(),
                      call.workflowAggregateId())))
      .describedAs(args -> "starting the workflow")
      .build();

  /**
   * Completing an asynchronous task: a <code>&#64;WorkflowTask</code> method with a
   * <code>&#64;TaskId</code> parameter, which returned without completing. The task ID
   * travels in {@link PhaseTwoCall#args()} under {@link PhaseTwoCall#ARG_TASK_ID}. No
   * adapter ID is persisted. The executing adapter is elected at dispatch time, by asking
   * the prioritized adapters.
   */
  public static final PhaseOperation COMPLETE_TASK = named("COMPLETE_TASK")
      .electedBy(Election.HOLDS_THE_TASK)
      .requiredOfEveryAdapter()
      .idempotencyKey(PhaseOperation::taskKey)
      .describedAs(args -> "completing task '%s'".formatted(args.get(PhaseTwoCall.ARG_TASK_ID)))
      .build();

  /**
   * Canceling an asynchronous task by BPMN error. The task ID and the BPMN error code
   * travel in {@link PhaseTwoCall#args()} under {@link PhaseTwoCall#ARG_TASK_ID} and
   * {@link PhaseTwoCall#ARG_BPMN_ERROR_CODE}. Only the task ID is part of the
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
   * Completing a USER task. It has the same shape as {@link #COMPLETE_TASK} (task ID in
   * {@link PhaseTwoCall#ARG_TASK_ID}, no adapter ID persisted), but it is a question of
   * its own, because user-task IDs live in another namespace than service-task IDs.
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
   * No adapter ID is persisted. The executing adapter is elected at dispatch time, by
   * asking the prioritized adapters.
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
   * Starting a workflow by a message start event. The rules of a start apply. The adapter
   * elected in phase one IS persisted with the entry, and the idempotency key is the key
   * of {@link #START_WORKFLOW} (one workflow per aggregate). The message name travels
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
   * under {@link PhaseTwoCall#ARG_SIGNAL_NAME}. The broadcasting adapter IS persisted
   * with the entry. A broadcast goes to every BPMS the workflow module was deployed to,
   * so each of them gets its own entry. There is NO idempotency key. A signal has nothing
   * to deduplicate by, and the same signal may rightly be broadcast again and again.
   * <p>
   * Not every adapter has to support it. A BPMS without signals says so through the
   * message below, and does not swallow a broadcast.
   */
  public static final PhaseOperation SEND_SIGNAL = named("SEND_SIGNAL")
      .electedBy(Election.EVERY_DEPLOYED_BPMS)
      .describedAs(args -> "broadcasting signal '%s'".formatted(args.get(PhaseTwoCall.ARG_SIGNAL_NAME)))
      .remedyWhenUnsupported(
          "Remove the adapter from the prioritized adapters of this workflow module, or replace the "
              + "signal by a message correlated to the workflow which waits for it.")
      .build();

  /**
   * Pushing the values of a changed workflow-aggregate which are shared with the BPMS
   * (<code>&#64;SyncWithBPMS</code>). An optional task ID travels in
   * {@link PhaseTwoCall#args()} under {@link PhaseTwoCall#ARG_TASK_ID}. Without it the
   * values are written at the global scope of the workflow. No adapter ID is persisted.
   * The executing adapter is elected at dispatch time, by asking the adapters.
   * <p>
   * There is NO idempotency key, and that is not a shortcut. The values are read from the
   * aggregate when the entry is DISPATCHED, so a redelivered entry writes the state of
   * that moment. Deduplicating could only drop a push, never save one.
   * <p>
   * Not every adapter has to support it. A BPMS which cannot update a running instance
   * says so, and does not pretend the push happened.
   */
  public static final PhaseOperation AGGREGATE_CHANGED = named("AGGREGATE_CHANGED")
      .electedBy(Election.HOLDS_THE_WORKFLOW)
      .describedAs(args -> "pushing the changed aggregate")
      .remedyWhenUnsupported(
          "Remove the adapter from the prioritized adapters of this workflow module, or model a task "
              + "the workflow waits at - completing it pushes the aggregate as well.")
      .build();

  /**
   * All operations owned by the VanillaBP core. Their names are reserved. If an
   * extension registers one of them,
   * {@link PhaseOperationRegistry#register(PhaseOperation, PhaseOperationDispatch)} rejects it.
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

  /**
   * Refuses an operation which misses one of its parts or has a blank name. This happens
   * when it is built, not at its first call.
   */
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
   * What this operation does, and to what, in the words a message uses.
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
   * namespace of the extension (<code>my-extension:MY_OPERATION</code>, see
   * {@link #NAMESPACE_SEPARATOR}). {@link Builder#build()} checks it.
   * <p>
   * Without further calls, the extension dispatches the operation itself
   * ({@link Election#OWN_DISPATCH}), and it is never deduplicated. Some extension operations
   * address a workflow like a core operation does. Such an extension picks the election which
   * says so ({@link Builder#electedBy(Election)}), and contributes a handler per adapter
   * instead of a dispatch of its own.
   *
   * @param name The namespaced name of the operation. It is persisted, so treat it as a
   *        contract
   * @return The builder
   */
  public static Builder extensionOperation(
      final String name) {

    return new Builder(name, true);

  }

  /**
   * Finds the core operation a name stands for. An empty answer means the name is not one
   * of VanillaBP's. {@link PhaseOperationRegistry} uses this to tell the registration of a
   * core operation from the registration of an extension.
   *
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
   * The names of all core operations, in the order of {@link #CORE_OPERATIONS}. Used for
   * messages which guide the developer.
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
   * Collects what an operation is made of. Everything except the name has a default. So
   * a definition names only what makes it different. An operation which is never
   * deduplicated says nothing about a key. An operation which an extension dispatches
   * itself says nothing about an election.
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
     * Sets the rule which derives the key that calls of this operation are deduplicated by.
     *
     * @param idempotencyKey The rule which derives the idempotency key of a call, or
     *        <code>call -&gt; Optional.empty()</code> (the default) for operations
     *        which must not be deduplicated. Name the operation in the key, as the core
     *        operations do at the start of their keys. Leave it out only if two of your
     *        operations should deduplicate against each other
     * @return This builder
     */
    public Builder idempotencyKey(
        final IdempotencyKey idempotencyKey) {

      this.idempotencyKey = idempotencyKey;
      return this;

    }

    /**
     * Says which BPMS executes the operation. So it also says which question the core
     * asks before it dispatches a call.
     *
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
     * this operation. So if an adapter has no handler for it, the boot fails, and not the
     * first call of the application.
     *
     * @return This builder
     */
    public Builder requiredOfEveryAdapter() {

      this.requiredOfEveryAdapter = true;
      return this;

    }

    /**
     * States that the activation the call was planned in travels with it
     * ({@link PhaseTwoCall#ARG_ACTIVATION_ID}). An operation needs this if its calls have
     * to be told apart per activation of a BPMN element. An adapter needs it if its BPMS
     * deduplicates in a way of its own.
     *
     * @return This builder
     */
    public Builder carryingTheActivation() {

      this.carriesActivation = true;
      return this;

    }

    /**
     * Sets the phrase the core puts into its messages about this operation. Without it, a
     * message reads <code>executing '&lt;name&gt;'</code>.
     *
     * @param describe What the operation does, see {@link Describe}
     * @return This builder
     */
    public Builder describedAs(
        final Describe describe) {

      this.describe = describe;
      return this;

    }

    /**
     * Adds a sentence to the message a caller reads if no configured BPMS knows what the
     * operation addresses.
     *
     * @param hintWhenUnknown What to add if no configured BPMS knows what the
     *        operation addresses, see {@link Wording#hintWhenUnknown()}
     * @return This builder
     */
    public Builder hintingWhenUnknown(
        final String hintWhenUnknown) {

      this.hintWhenUnknown = hintWhenUnknown;
      return this;

    }

    /**
     * Adds a sentence to the message a caller reads if the elected adapter cannot
     * execute the operation at all.
     *
     * @param remedyWhenUnsupported What to add if the elected adapter cannot execute
     *        the operation, see {@link Wording#remedyWhenUnsupported()}
     * @return This builder
     */
    public Builder remedyWhenUnsupported(
        final String remedyWhenUnsupported) {

      this.remedyWhenUnsupported = remedyWhenUnsupported;
      return this;

    }

    /**
     * Builds the operation. For an operation of an extension, it checks the name before it
     * hands the operation out.
     *
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
   * The key of an operation on ONE task. The operation is part of it, because completing
   * and canceling one task are two operations. So are the asynchronous and the user-task
   * version of each. One task id used to give one key for all four of them.
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
      // the same message may rightly be correlated several times, so no
      // deduplication is possible (documented rest risk of at-least-once). An activation
      // would not help here: it would start to deduplicate what is not deduplicated on
      // purpose
      return Optional.empty();
    }
    final var key = "%s|%s|%s|%s|%s|%s".formatted(
        call.operation(),
        call.workflowModuleId(),
        call.bpmnProcessId(),
        call.workflowAggregateId(),
        call.args().get(PhaseTwoCall.ARG_MESSAGE_NAME),
        correlationId);
    // Multi-instance siblings of one aggregate agree in every part above, because a
    // called process is a secondary workflow of the SAME aggregate. So the activation
    // which planned the correlation tells them apart. It is read from the call and not
    // from the thread. That keeps this derivation a pure function of what a store
    // persists, and the same value can reach the adapter at dispatch time. Outside an
    // invocation there is no activation, and the key is exactly what it always was
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
