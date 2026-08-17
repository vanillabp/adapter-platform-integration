package io.vanillabp.integration.runtime.mongo;

import com.mongodb.client.ClientSession;

import io.quarkus.mongodb.panache.runtime.JavaMongoOperations;

/**
 * The one place naming MongoDB Panache, loaded only after {@link MongoSessions} found it
 * on the classpath.
 */
final class PanacheMongoSession {

  private PanacheMongoSession() {
  }

  /**
   * The session Panache keeps for the running JTA transaction, started on the default
   * MongoDB client if the transaction does not have one yet.
   *
   * @return The session or <code>null</code> if Panache did not provide one
   */
  static ClientSession current() {

    return JavaMongoOperations.INSTANCE.getSession();

  }

}
