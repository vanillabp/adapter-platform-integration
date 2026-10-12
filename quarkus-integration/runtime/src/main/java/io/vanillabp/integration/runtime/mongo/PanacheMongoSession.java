package io.vanillabp.integration.runtime.mongo;

import com.mongodb.client.ClientSession;

import io.quarkus.mongodb.panache.runtime.JavaMongoOperations;

/**
 * The one place which names MongoDB Panache. It is loaded only after {@link MongoSessions}
 * found Panache on the classpath.
 */
final class PanacheMongoSession {

  private PanacheMongoSession() {
  }

  /**
   * The session which Panache keeps for the running JTA transaction. If the transaction has
   * none yet, Panache starts one on the default MongoDB client.
   *
   * @return The session or <code>null</code> if Panache did not provide one
   */
  static ClientSession current() {

    return JavaMongoOperations.INSTANCE.getSession();

  }

}
