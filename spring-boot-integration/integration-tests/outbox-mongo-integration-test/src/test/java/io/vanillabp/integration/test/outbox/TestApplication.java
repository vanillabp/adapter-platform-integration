package io.vanillabp.integration.test.outbox;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * Test application for the MongoDB {@link io.vanillabp.integration.spi.PhaseTwoOutbox}:
 * the dummy adapter is forced to require a two-phase commit for starting workflows
 * (property <code>dummy-adapter.at-least-once-delivery</code>) and a
 * {@link RecordingPhaseTwoListener} observes (and optionally fails) phase two. A
 * {@link MongoTransactionManager} is defined so aggregate and outbox entry are written
 * in one MongoDB transaction (the TestContainers MongoDB runs as a replica set).
 */
@SpringBootApplication
public class TestApplication {

  @Bean
  public MongoTransactionManager transactionManager(
      final MongoDatabaseFactory databaseFactory) {

    return new MongoTransactionManager(databaseFactory);

  }

  @Bean
  public RecordingPhaseTwoListener recordingPhaseTwoListener() {

    return new RecordingPhaseTwoListener();

  }

}
