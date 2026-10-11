package io.vanillabp.integration.extension.spi.handler;

import java.util.Optional;

/**
 * Binds the parameters VanillaBP knows nothing about. An extension adds one binder for
 * each kind of parameter its own SPI adds. The Business Cockpit brings the prefilled
 * details object and its <code>&#64;DetailsEvent</code> parameter that way.
 * <p>
 * A binder is asked once per parameter while the handler method is scanned. It answers
 * with the {@link HandlerValueSource} which produces the value for each invocation. If
 * the binder rejects the parameter (an empty answer), the parameter goes on to the next
 * binder, and finally to the core binders the contract allows. If nobody serves a
 * parameter, the application does not start. The message names the method, the
 * parameter and what may stand there.
 * <p>
 * Sometimes a binder recognizes the parameter but finds it wrong, for example the right
 * annotation on the wrong type. Then it throws instead of rejecting. So the developer
 * reads what is wrong, not just "unknown parameter".
 */
@FunctionalInterface
public interface HandlerParameterBinder {

  /**
   * Decides whether this binder serves the parameter. If it does, it says how the value is
   * produced. It is asked once per parameter while the method is scanned, never per
   * invocation.
   *
   * @param parameter The parameter to bind
   * @return How to produce its value, or empty if this binder does not serve it
   */
  Optional<HandlerValueSource> bind(
      HandlerParameter parameter);

}
