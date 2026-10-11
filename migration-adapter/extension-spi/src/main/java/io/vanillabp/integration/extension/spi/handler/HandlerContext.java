package io.vanillabp.integration.extension.spi.handler;

/**
 * Where one invocation of a handler method takes the values of its parameters from: the
 * workflow aggregate VanillaBP loaded, the process variables the caller passed, and the
 * extension's own payload.
 *
 * @see HandlerValueSource
 */
public interface HandlerContext {

  /**
   * The aggregate this invocation runs for. A parameter which takes the aggregate by its
   * type gets this value. It is <code>null</code> if the call carried none.
   *
   * @return The workflow aggregate of the workflow the handler runs for
   */
  Object getWorkflowAggregate();

  /**
   * The object the extension passed to {@link ExtensionHandlers#invoke(HandlerCall)}. The
   * binders of the extension read it. The Business Cockpit passes its prefilled details
   * object here.
   *
   * @return The payload, or <code>null</code> if the call carried none
   */
  Object getPayload();

  /**
   * The payload, cast to the type the binder which asks for it expects.
   *
   * @param <T> The expected type
   * @param type The expected type
   * @return The payload
   * @throws IllegalStateException If the payload is missing or of another type. That is a
   *           bug of whoever built the call, and the message says so
   */
  default <T> T payload(
      final Class<T> type) {

    final var payload = getPayload();
    if (!type.isInstance(payload)) {
      throw new IllegalStateException(
          """
              The handler invocation carries %s as its payload, but a parameter of the method needs \
              a '%s'! Whoever built the invocation has to pass one."""
              .formatted(
                  payload == null
                      ? "nothing"
                      : "a '%s'".formatted(payload.getClass().getName()),
                  type.getName()));
    }
    return type.cast(payload);

  }

  /**
   * The value of one process variable of this invocation, as the caller passed it, before
   * it is converted to the type of a parameter.
   *
   * @param name The name of the process variable
   * @return Its value, or <code>null</code> if the call carried none of that name
   */
  Object getVariable(
      String name);

  /**
   * The multi-instance scopes this invocation runs in, one per BPMN element which has
   * multi-instance characteristics. Read a scope by its element id, or walk through the
   * map. Nested elements come outermost first. This is true for a workflow task and for a
   * {@link HandlerCall} an extension built (see
   * {@code NestedMultiInstancesKeepTheirOrderTest}).
   *
   * @return The multi-instance scopes of this invocation, keyed by BPMN element id
   */
  java.util.Map<String, HandlerMultiInstance> getMultiInstances();

}
