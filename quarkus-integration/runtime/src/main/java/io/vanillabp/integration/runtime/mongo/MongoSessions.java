package io.vanillabp.integration.runtime.mongo;

import com.mongodb.client.ClientSession;

import jakarta.transaction.Status;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Access to the MongoDB session of the running JTA transaction, which is what makes
 * VanillaBP's own MongoDB stores atomic with a workflow aggregate stored in MongoDB.
 * <p>
 * MongoDB Panache enlists itself: writing an entity inside a JTA transaction makes it
 * start a {@link ClientSession}, begin a MongoDB transaction on it, keep the session as a
 * transaction resource and commit or abort it when the JTA transaction completes. The
 * phase-two outbox and the log of processed task deliveries write through the MongoDB
 * client directly, so unless they use that very session, their writes are outside the
 * transaction which persists the aggregate - which is exactly the atomicity
 * {@link io.vanillabp.integration.spi.PhaseTwoOutbox#schedule} promises.
 * <p>
 * Whether a session is available depends on the application: MongoDB Panache has to be on
 * the classpath (it is optional for this extension - an application may use the MongoDB
 * client without it) and a JTA transaction has to be active. Where there is none, the
 * stores keep their previous behaviour: write immediately and compensate best-effort on
 * rollback.
 */
public final class MongoSessions {

  private static final boolean PANACHE_AVAILABLE = isPanacheAvailable();

  private MongoSessions() {
  }

  /**
   * The session of the running transaction, starting one through Panache if the
   * transaction has none yet - so it does not matter whether the aggregate or the outbox
   * entry is written first, both end up in the same MongoDB transaction.
   *
   * @param transactionRegistry The JTA registry telling whether a transaction is active
   * @return The session, or <code>null</code> if MongoDB Panache is not available or no
   *         transaction is active
   */
  public static ClientSession activeSession(
      final TransactionSynchronizationRegistry transactionRegistry) {

    if (!PANACHE_AVAILABLE) {
      return null;
    }
    if ((transactionRegistry == null) || (transactionRegistry.getTransactionStatus() != Status.STATUS_ACTIVE)) {
      return null;
    }
    return PanacheMongoSession.current();

  }

  /**
   * Whether MongoDB Panache is on the classpath. Asked by name, so this class stays
   * loadable in an application using the plain MongoDB client.
   */
  private static boolean isPanacheAvailable() {

    try {
      Class
          .forName(
              "io.quarkus.mongodb.panache.runtime.JavaMongoOperations",
              false,
              MongoSessions.class.getClassLoader());
      return true;
    } catch (final ClassNotFoundException | LinkageError notThere) {
      return false;
    }

  }

}
