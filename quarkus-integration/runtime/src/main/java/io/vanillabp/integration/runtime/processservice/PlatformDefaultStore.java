package io.vanillabp.integration.runtime.processservice;

/**
 * What the resolvers need to know about a store which VanillaBP brings itself. Such stores
 * are the phase-two outbox and the log of processed task deliveries, with one implementation
 * per persistence technology. The resolvers need to know which technology a store serves,
 * and whether it can serve at all. A default without a datasource, or without a MongoDB
 * client, stores nothing. It must not be given to an aggregate.
 * <p>
 * Both answers used to come from <code>instanceof</code> checks on the concrete classes. So
 * {@link QuarkusPhaseTwoOutboxResolver} and {@link QuarkusTaskDeliveryLogResolver} named the
 * MongoDB classes. A native image resolves every referenced method while it is built. So
 * those two classes ended the build of an application which never asked for MongoDB. Asking
 * an interface keeps the MongoDB defaults where they belong: reachable only where the
 * extension which registers them is.
 */
public interface PlatformDefaultStore {

  /**
   * Which technology this default belongs to. A resolver compares the answer with the
   * technology which manages an aggregate. So a default which names the wrong one would be
   * given to aggregates whose transaction it does not take part in.
   *
   * @return The persistence technology whose transaction this store takes part in, never
   *         {@link QuarkusPersistenceTechnology.Technology#UNKNOWN}
   */
  QuarkusPersistenceTechnology.Technology technology();

  /**
   * Whether this default can be used. The extension registers the bean at build time. But
   * without the connection behind it, there is nothing to write into. A default which
   * cannot be used must not be chosen for an aggregate. The startup check then names the
   * fixes.
   *
   * @return Whether the store can be used
   */
  boolean isAvailable();

}
