package io.vanillabp.integration.extension.spi.handler;

/**
 * What one invocation of a handler method binds its parameters from: the workflow
 * aggregate VanillaBP loaded, the process variables the caller passed and the
 * extension's own payload.
 *
 * @see HandlerValueSource
 */
public interface HandlerContext {

  /**
   * The aggregate this invocation runs for - what a parameter taking the aggregate by its
   * type is bound to. It is <code>null</code> where the call carried none.
   *
   * @return The workflow aggregate of the workflow the handler runs for
   */
  Object getWorkflowAggregate();

  /**
   * The object the extension handed to {@link ExtensionHandlers#invoke(HandlerCall)},
   * which is what the binders of the extension read. The Business Cockpit passes its
   * prefilled details object here.
   *
   * @return The payload, or <code>null</code> if the call carried none
   */
  Object getPayload();

  /**
   * The payload, cast to what the binder asking for it expects.
   *
   * @param <T> The expected type
   * @param type The expected type
   * @return The payload
   * @throws IllegalStateException If the payload is missing or of another type - which
   *           is a defect of whoever built the call, and the message says so
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
   * The value of one process variable of this invocation, as the caller passed it and
   * before any conversion to a parameter's type.
   *
   * @param name The name of the process variable
   * @return Its value, or <code>null</code> if the call carried none of that name
   */
  Object getVariable(
      String name);

  /**
   * The multi-instance scopes this invocation runs in, one per BPMN element carrying
   * multi-instance characteristics. Read a scope by the element id it is keyed under, or
   * walk the map: nested elements come outermost first, whether the invocation is a
   * workflow task or a {@link HandlerCall} an extension built (see
   * {@code NestedMultiInstancesKeepTheirOrderTest}).
   *
   * @return The multi-instance scopes of this invocation, keyed by BPMN element id
   */
  java.util.Map<String, HandlerMultiInstance> getMultiInstances();

}
