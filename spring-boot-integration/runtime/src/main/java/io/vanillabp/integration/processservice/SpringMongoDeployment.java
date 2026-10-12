package io.vanillabp.integration.processservice;

import org.bson.Document;
import org.springframework.context.ApplicationContext;
import org.springframework.data.mongodb.core.MongoTemplate;

/**
 * Asks a MongoDB deployment whether it is a replica set. Every MongoDB transaction needs
 * one. Without it, a session started by a <code>MongoTransactionManager</code> fails at
 * its first write with "Transaction numbers are only allowed on a replica set member or
 * mongos".
 * <p>
 * This is a class of its own, because <code>spring-data-mongodb</code> is optional here.
 * It is only loaded where {@link SpringTransactionRunnerResolver} found MongoDB in the
 * application.
 */
final class SpringMongoDeployment {

  private SpringMongoDeployment() {
  }

  /**
   * Whether the deployment behind the application's {@link MongoTemplate} is a replica
   * set, or a sharded cluster. For a sharded cluster, <code>hello</code> answers with a
   * message instead of a replica-set name.
   *
   * @param applicationContext The context holding the template
   * @return <code>true</code> if it is, <code>false</code> if it demonstrably is not,
   *         <code>null</code> if the question could not be answered (no template, no
   *         connection, a server refusing the command). A question without an answer must
   *         never turn into a verdict
   */
  static Boolean isReplicaSet(
      final ApplicationContext applicationContext) {

    final var mongoTemplate = applicationContext
        .getBeanProvider(MongoTemplate.class)
        .getIfAvailable();
    if (mongoTemplate == null) {
      return null;
    }
    try {
      final var hello = mongoTemplate.executeCommand(new Document("hello", 1));
      return (hello.get("setName") != null) || "isdbgrid".equals(hello.get("msg"));
    } catch (final RuntimeException unanswered) {
      return null;
    }

  }

}
