package io.vanillabp.integration.runtime.mongo;

import org.bson.Document;

import com.mongodb.client.MongoClient;

import io.vanillabp.integration.runtime.processservice.MongoDeploymentProbe;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

/**
 * Asks the MongoDB deployment of the application whether it is a replica set, by running the
 * <code>hello</code> command against it.
 * <p>
 * The one place naming a MongoDB type on the way to the coverage verdict. It is
 * registered as a bean only where the <code>quarkus-mongodb-client</code> extension is
 * present, so an application without MongoDB never links it - which is what lets such an
 * application be built as a native image.
 */
@Singleton
public class MongoClientDeploymentProbe implements MongoDeploymentProbe {

  @Inject
  Instance<MongoClient> mongoClients;

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
