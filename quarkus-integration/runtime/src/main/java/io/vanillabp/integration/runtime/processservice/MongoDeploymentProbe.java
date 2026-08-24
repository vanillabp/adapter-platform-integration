package io.vanillabp.integration.runtime.processservice;

/**
 * Answers whether the MongoDB deployment of the application is a replica set, which is the
 * condition of every MongoDB transaction - and MongoDB Panache starts one whenever it writes
 * inside a JTA transaction, so on a standalone server that write fails with "Transaction
 * numbers are only allowed on a replica set member or mongos".
 * <p>
 * An interface, because the answer is only available where the MongoDB client extension is:
 * its implementation is registered as a bean by a build step guarded by
 * <code>Capability.MONGODB_CLIENT</code>, the way the phase-two outbox and the log of
 * processed task deliveries are. Nothing on this side of the interface names a MongoDB type,
 * so {@link QuarkusTransactionRunnerResolver} links in an application which never asked for
 * MongoDB - a native image resolves every referenced method while it is built, and a direct
 * call into the driver used to end that build.
 */
public interface MongoDeploymentProbe {

  /**
   * @return <code>true</code> for a replica set or a sharded cluster, <code>false</code> if
   *         the deployment demonstrably is neither, <code>null</code> if the question could
   *         not be answered (no client, no connection, a server refusing the command) - an
   *         unanswered question must never turn into a verdict
   */
  Boolean isReplicaSet();

}
