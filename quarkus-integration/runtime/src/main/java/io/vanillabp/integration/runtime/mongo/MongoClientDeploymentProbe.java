package io.vanillabp.integration.runtime.mongo;

import org.bson.Document;

import com.mongodb.client.MongoClient;

import io.vanillabp.integration.runtime.processservice.MongoDeploymentProbe;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Asks the MongoDB deployment of the application whether it is a replica set. It runs the
 * <code>hello</code> command for that.
 * <p>
 * It is the one place on the way to the coverage verdict which names a MongoDB type. It is
 * registered as a bean only where the <code>quarkus-mongodb-client</code> extension is
 * there. So an application without MongoDB never links it. This is what lets such an
 * application be built as a native image.
 */
@Singleton
public class MongoClientDeploymentProbe implements MongoDeploymentProbe {

  @Inject
  Instance<MongoClient> mongoClients;

  /**
   * The CDI container creates it, and only where the MongoDB extension is there. The client
   * itself may still be missing. So it is injected as an {@link Instance}, and we check that
   * it is there before we use it.
   */
  public MongoClientDeploymentProbe() {
  }

  @Override
  public Boolean isReplicaSet() {

    if (!mongoClients.isResolvable()) {
      return null;
    }
    try {
      final var hello = mongoClients
          .get()
          .getDatabase("admin")
          .runCommand(new Document("hello", 1));
      return (hello.get("setName") != null) || "isdbgrid".equals(hello.get("msg"));
    } catch (final RuntimeException unanswered) {
      return null;
    }

  }

}
