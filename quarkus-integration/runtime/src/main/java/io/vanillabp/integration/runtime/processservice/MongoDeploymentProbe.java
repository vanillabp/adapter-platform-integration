package io.vanillabp.integration.runtime.processservice;

/**
 * Answers whether the MongoDB deployment of the application is a replica set. Every MongoDB
 * transaction needs one. MongoDB Panache starts such a transaction whenever it writes inside
 * a JTA transaction. So on a standalone server, that write fails with "Transaction numbers
 * are only allowed on a replica set member or mongos".
 * <p>
 * This is an interface, because the answer is there only where the MongoDB client extension
 * is. A build step registers the implementation as a bean only under
 * <code>Capability.MONGODB_CLIENT</code>. The phase-two outbox and the log of processed task
 * deliveries are registered the same way. Nothing on this side of the interface names a
 * MongoDB type. So {@link QuarkusTransactionRunnerResolver} links in an application which
 * never asked for MongoDB. A native image resolves every referenced method while it is
 * built, and a direct call into the driver used to end that build.
 */
public interface MongoDeploymentProbe {

  /**
   * Asks the deployment what it is. The question goes to the server and not to the
   * configured connection string, because a single host may be a replica set with one node.
   * It is asked while the application starts, when the core asks how far an aggregate's
   * transaction reaches. So a slow answer delays a boot, but no write.
   *
   * @return <code>true</code> for a replica set or a sharded cluster, <code>false</code> if
   *         the deployment provably is neither, <code>null</code> if there is no answer (no
   *         client, no connection, a server which refuses the command). A question without
   *         an answer must never turn into a verdict
   */
  Boolean isReplicaSet();

}
