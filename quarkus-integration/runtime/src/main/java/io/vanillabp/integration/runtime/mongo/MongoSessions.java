package io.vanillabp.integration.runtime.mongo;

import com.mongodb.client.ClientSession;

import jakarta.transaction.Status;
import jakarta.transaction.TransactionSynchronizationRegistry;

/**
 * Access to the MongoDB session of the running JTA transaction. With it, VanillaBP's own
 * MongoDB stores write atomically together with a workflow aggregate stored in MongoDB.
 * <p>
 * MongoDB Panache joins the JTA transaction by itself. When it writes an entity inside a JTA
 * transaction, it starts a {@link ClientSession} and begins a MongoDB transaction on it. It
 * keeps the session as a transaction resource. It commits or aborts it when the JTA
 * transaction completes. The phase-two outbox and the log of processed task deliveries write
 * through the MongoDB client directly. If they do not use that same session, their writes
 * are outside the transaction which persists the aggregate. But
 * {@link io.vanillabp.integration.spi.PhaseTwoOutbox#schedule} promises exactly that
 * atomicity.
 * <p>
 * Whether a session is there depends on the application. MongoDB Panache has to be on the
 * classpath. It is optional for this extension, because an application may use the MongoDB
 * client without it. And a JTA transaction has to be active. Where there is no session, the
 * stores keep their previous behaviour. They write at once and try their best to undo the
 * write on rollback.
 */
public final class MongoSessions {

  private static final boolean PANACHE_AVAILABLE = isPanacheAvailable();

  private MongoSessions() {
  }

  /**
   * The session of the running transaction. If the transaction has none yet, Panache starts
   * one. So it does not matter whether the aggregate or the outbox entry is written first.
   * Both end up in the same MongoDB transaction.
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
   * Whether MongoDB Panache is on the classpath. We ask by class name. So this class can still
   * be loaded in an application which uses the plain MongoDB client.
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
