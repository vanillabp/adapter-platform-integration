package io.vanillabp.integration.extension.spi.handler;

/**
 * Produces the value of one handler-method parameter, once per invocation. A binder
 * returns it while the method is scanned. So everything which can be decided at
 * startup is decided there, and the invocation only reads.
 */
@FunctionalInterface
public interface HandlerValueSource {

  /**
   * Produces the value of the parameter for one invocation. It runs on the thread of the
   * handler and inside its transaction. So it should only read the context.
   *
   * @param context The values of this invocation
   * @return The value to pass as the parameter
   */
  Object valueFor(
      HandlerContext context);

}
