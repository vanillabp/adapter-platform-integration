package io.vanillabp.integration.runtime.processservice;

import org.bson.Document;

import com.mongodb.client.MongoClient;

import io.quarkus.arc.Arc;

/**
 * Asks a MongoDB deployment whether it is a replica set, which is the condition of every
 * MongoDB transaction - and MongoDB Panache starts one whenever it writes inside a JTA
 * transaction, so on a standalone server that write fails with "Transaction numbers are
 * only allowed on a replica set member or mongos" (story 70).
 * <p>
 * A class of its own, because the MongoDB client extension is optional here: it is only
 * loaded where a MongoDB-based aggregate persistence was found.
 */
final class QuarkusMongoDeployment {

  private QuarkusMongoDeployment() {
  }

  /**
   * Looks the MongoDB client up itself, so no caller has to name a MongoDB type: this
   * class is only ever loaded where a MongoDB-based aggregate persistence was found, which
   * means the extension is there.
   *
   * @return <code>true</code> for a replica set or a sharded cluster,
   *         <code>false</code> if it demonstrably is neither, <code>null</code> if the
   *         question could not be answered (no client, no connection, a server refusing
   *         the command) - an unanswered question must never turn into a verdict
   */
  static Boolean isReplicaSet() {

    final var client = Arc
        .container()
        .instance(MongoClient.class);
    if (!client.isAvailable()) {
      return null;
    }
    try {
      final var hello = client
          .get()
          .getDatabase("admin")
          .runCommand(new Document("hello", 1));
      return (hello.get("setName") != null) || "isdbgrid".equals(hello.get("msg"));
    } catch (final RuntimeException unanswered) {
      return null;
    }

  }

}
