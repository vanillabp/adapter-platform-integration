package io.vanillabp.integration.test.outbox;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;

/**
 * Test application for the MongoDB {@link io.vanillabp.integration.spi.PhaseTwoOutbox}.
 * The dummy adapter plays a BPMS which repeats deliveries (property
 * <code>dummy-adapter.at-least-once-delivery</code>). A {@link RecordingPhaseTwoListener}
 * watches phase two and can make it fail. The application defines a
 * {@link MongoTransactionManager}, so aggregate and outbox entry are written in one
 * MongoDB transaction (the Testcontainers MongoDB runs as a replica set).
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
