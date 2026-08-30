package io.vanillabp.integration.spi;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All phase operations known to this application: VanillaBP's core operations
 * plus the operations extensions contribute. The registry replaces what used to be
 * a closed enum - an outbox entry persists the operation's NAME, and the name is
 * resolved here at dispatch time.
 * <p>
 * The core registers its operations while the core's phase-two router is built, so
 * they are available before the first entry can be dispatched. Extensions register
 * theirs at startup:
 *
 * <pre>
 * registry.register(
 *     PhaseOperation.extensionOperation("my-extension:NOTIFY", call -&gt; Optional.of(...)),
 *     (call, previouslyAttempted) -&gt; notify(call));
 * </pre>
 *
 * An extension operation is dispatched to the extension's own
 * {@link PhaseOperationDispatch} - the aggregate-ID-to-adapter election of the
 * core operations does not apply to it.
 * <p>
 * An outbox store never interprets an operation: it persists the name, the arguments
 * and the idempotency key, and hands them back unchanged. What a store written by an
 * application does ask for is {@link #dispatchFor(String)}, the dispatch belonging to a
 * name it read back, so that it can hand the call on without knowing what the operation
 * means.
 */
public final class PhaseOperationRegistry {

  private record Registration(
                              PhaseOperation operation,
                              PhaseOperationDispatch dispatch) {
  }

  private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

  /**
   * Register one of VanillaBP's core operations - called by the core itself. An
   * operation which is not a core operation is rejected: extensions use
   * {@link #register(PhaseOperation, PhaseOperationDispatch)}.
   *
   * @param operation The core operation, one of
   *        {@link PhaseOperation#CORE_OPERATIONS}
   * @param dispatch What to do with a call of that operation after the commit
   * @throws IllegalArgumentException If the operation is not a core operation
   * @throws IllegalStateException If an operation of that name is already
   *         registered
   */
  public void registerCoreOperation(
      final PhaseOperation operation,
      final PhaseOperationDispatch dispatch) {

    if (PhaseOperation.coreOperation(operation.name()).isEmpty()) {
      throw new IllegalArgumentException(
          """
              The phase operation '%s' is not one of VanillaBP's core operations (%s)! Operations \
              contributed by an extension are registered by 'register' and have to be namespaced \
              (e.g. 'my-extension%sMY_OPERATION')."""
              .formatted(
                  operation.name(),
                  String.join(", ", PhaseOperation.coreOperationNames()),
                  PhaseOperation.NAMESPACE_SEPARATOR));
    }
    add(operation, dispatch);

  }

  /**
   * Register an operation contributed by an extension. Its name has to be
   * namespaced (see
   * {@link PhaseOperation#extensionOperation(String, PhaseOperation.IdempotencyKey)}),
   * which keeps it distinct from VanillaBP's core operations and from the
   * operations of other extensions.
   *
   * @param operation The extension's operation
   * @param dispatch What to do with a call of that operation after the commit
   * @throws IllegalArgumentException If the name is a core operation's name or is
   *         not namespaced (guiding message)
   * @throws IllegalStateException If an operation of that name is already
   *         registered
   */
  public void register(
      final PhaseOperation operation,
      final PhaseOperationDispatch dispatch) {

    if (PhaseOperation.coreOperation(operation.name()).isPresent()) {
      throw new IllegalArgumentException(
          """
              The phase operation name '%s' is reserved for VanillaBP's core operations (%s)! An \
              extension has to namespace its operations (e.g. 'my-extension%s%s') - the name is \
              persisted in the outbox store, so it has to stay unique across the whole application."""
              .formatted(
                  operation.name(),
                  String.join(", ", PhaseOperation.coreOperationNames()),
                  PhaseOperation.NAMESPACE_SEPARATOR,
                  operation.name()));
    }
    PhaseOperation.validateNamespaced(operation);
    add(operation, dispatch);

  }

  /**
   * @param name The persisted name of an operation
   * @return The registered operation of that name, if any
   */
  public Optional<PhaseOperation> find(
      final String name) {

    return Optional
        .ofNullable(registrations.get(name))
        .map(Registration::operation);

  }

  /**
   * @param name The persisted name of an operation
   * @return The dispatch registered for that operation, if any
   */
  public Optional<PhaseOperationDispatch> dispatchFor(
      final String name) {

    return Optional
        .ofNullable(registrations.get(name))
        .map(Registration::dispatch);

  }

  /**
   * The names of all registered operations, sorted - used for guiding messages.
   *
   * @return The registered operations' names
   */
  public List<String> registeredNames() {

    return registrations
        .keySet()
        .stream()
        .sorted()
        .toList();

  }

  private void add(
      final PhaseOperation operation,
      final PhaseOperationDispatch dispatch) {

    final var previous = registrations
        .putIfAbsent(operation.name(), new Registration(operation, dispatch));
    if (previous != null) {
      throw new IllegalStateException(
          """
              The phase operation '%s' is registered twice! Every operation is registered exactly \
              once - by VanillaBP for its core operations, by the contributing extension for its own. \
              Check whether the extension registers on every startup event it observes, or whether two \
              extensions claim the same name."""
              .formatted(operation.name()));
    }

  }

}
