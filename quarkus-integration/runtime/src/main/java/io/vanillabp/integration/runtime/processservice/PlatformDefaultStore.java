package io.vanillabp.integration.runtime.processservice;

/**
 * What the resolvers need to know about a store VanillaBP brings itself - the phase-two
 * outbox and the log of processed task deliveries, one implementation per persistence
 * technology: which technology it serves, and whether it can serve at all (a default
 * without a datasource respectively without a MongoDB client stores nothing and must not
 * be attributed to an aggregate).
 * <p>
 * Both answers used to be read by asking <code>instanceof</code> for the concrete classes,
 * which named the MongoDB ones in {@link QuarkusPhaseTwoOutboxResolver} and
 * {@link QuarkusTaskDeliveryLogResolver}. A native image resolves every referenced method
 * while it is built, so those two classes ended the build of an application which never
 * asked for MongoDB. Asking an interface instead keeps the MongoDB defaults
 * where they belong: reachable only where the extension registering them is.
 */
public interface PlatformDefaultStore {

  /**
   * @return The persistence technology whose transaction this store takes part in, never
   *         {@link QuarkusPersistenceTechnology.Technology#UNKNOWN}
   */
  QuarkusPersistenceTechnology.Technology technology();

  /**
   * Whether this default is usable: the extension registers the bean at build time, but
   * without the connection behind it there is nothing to write into - and an unusable
   * default must not be selected for an aggregate (the startup validation then names the
   * remedies).
   *
   * @return Whether the store can be used
   */
  boolean isAvailable();

}
