package io.vanillabp.integration.test.outbox.parallel;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.ConnectionString;

import io.vanillabp.integration.test.outbox.CountingCommandListener;
import io.vanillabp.integration.test.outbox.TestApplication;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Which change of a workflow aggregate in MongoDB makes VanillaBP write it, on Spring Boot.
 * Each test runs one task on a {@link VersionedParallelAggregate} and reads the version
 * afterwards. Spring Data raises the version on every write. So a version which did not move
 * proves that nothing was written, and a version one higher proves one write.
 * <p>
 * VanillaBP compares the document as Spring Data would write it. A reference to a document
 * of another collection is written as the reference only. So a change in the referenced
 * document is not a change of the aggregate, while a reference which points elsewhere, is
 * added or is removed is one. An object mapped into the document as a sub-document is part
 * of it, and each change in there is a change.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = {
    TestApplication.class, WhatCountsAsAChangeTest.ChangeTestConfiguration.class
})
@Testcontainers
public class WhatCountsAsAChangeTest {

  private static final String PROCESS = "VersionedParallelProcess";

  private static final CountingCommandListener commands = new CountingCommandListener();

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB))
      // MongoDB transactions require a replica set
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  /**
   * Points the application to the container, counts the commands it sends and switches
   * auditing on.
   */
  @TestConfiguration
  @EnableMongoAuditing
  static class ChangeTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {
      return builder -> builder
          .applyConnectionString(new ConnectionString(mongoDb.getReplicaSetUrl()))
          .addCommandListener(commands);
    }

  }

  @Autowired
  private ParallelIterations iterations;

  @Autowired
  private ParallelScenario scenario;

  @Autowired
  private VersionedParallelAggregateRepository repository;

  @Autowired
  private IterationRecordRepository records;

  @Autowired
  private LazyRecordRepository lazyRecords;

  private VersionedParallelAggregate stored;

  @BeforeEach
  public void storeTheAggregate() {

    stored = scenario.store(new VersionedParallelAggregate(), 2);

  }

  @Test
  @DisplayName("A record changed behind a @DBRef is no change of the aggregate")
  public void aRecordChangedBehindADbRefIsNoChange() throws Exception {

    runAndExpectWrites("changeRecordBehindReference", 0);
    assertEquals(
        "changed behind the reference",
        records
            .findById("record-0")
            .orElseThrow()
            .getResult());

  }

  @Test
  @DisplayName("A record changed behind a @DocumentReference is no change of the aggregate")
  public void aRecordChangedBehindADocumentReferenceIsNoChange() throws Exception {

    runAndExpectWrites("changeRecordBehindDocumentReference", 0);
    assertEquals(
        "changed behind the reference",
        records
            .findById("document-record")
            .orElseThrow()
            .getResult());

  }

  @Test
  @DisplayName("A record changed behind a lazy @DBRef is no change of the aggregate")
  public void aRecordChangedBehindALazyDbRefIsNoChange() throws Exception {

    runAndExpectWrites("changeRecordBehindLazyReference", 0);
    assertEquals(
        "changed behind the reference",
        lazyRecords
            .findById("lazy-record")
            .orElseThrow()
            .getResult());

  }

  @Test
  @DisplayName("Comparing does not resolve a lazy @DBRef which the handler left alone")
  public void comparingDoesNotResolveALazyDbRef() throws Exception {

    commands.reset();

    runAndExpectWrites("leaveLazyReferenceAlone", 0);

    assertEquals(List.of(), commands.commandsOn("parallel-test-lazy-record"));

  }

  @Test
  @DisplayName("A @DBRef which points to another record is a change")
  public void aDbRefPointingElsewhereIsAChange() throws Exception {

    runAndExpectWrites("pointToAnotherRecord", 1);

  }

  @Test
  @DisplayName("An added @DBRef is a change")
  public void anAddedDbRefIsAChange() throws Exception {

    runAndExpectWrites("addAReference", 1);

  }

  @Test
  @DisplayName("A removed @DBRef is a change")
  public void aRemovedDbRefIsAChange() throws Exception {

    runAndExpectWrites("removeAReference", 1);

  }

  @Test
  @DisplayName("A @DocumentReference which points to another record is a change")
  public void aDocumentReferencePointingElsewhereIsAChange() throws Exception {

    runAndExpectWrites("pointDocumentReferenceToAnotherRecord", 1);

  }

  @Test
  @DisplayName("A lazy @DBRef which points to another record is a change")
  public void aLazyDbRefPointingElsewhereIsAChange() throws Exception {

    runAndExpectWrites("pointLazyReferenceToAnotherRecord", 1);

  }

  @Test
  @DisplayName("A change inside a sub-document is a change")
  public void aChangedSubDocumentIsAChange() throws Exception {

    runAndExpectWrites("changeSubDocument", 1);
    assertEquals(
        "changed in the sub-document",
        repository
            .findById("aggregate")
            .orElseThrow()
            .getDetails()
            .getNote());

  }

  /**
   * Runs the task once and checks how often the aggregate was written. A write raises the
   * version by one. The auditing field moves with every write as well, so it stays where it
   * was where nothing was written.
   */
  private void runAndExpectWrites(
      final String taskDefinition,
      final int writes) throws Exception {

    final var run = iterations.runOnce(PROCESS, taskDefinition);

    assertEquals(1, run.completed(), run.describe());
    final var afterwards = repository
        .findById("aggregate")
        .orElseThrow();
    assertEquals(stored.getVersion() + writes, afterwards.getVersion());
    if (writes == 0) {
      assertEquals(stored.getLastModified(), afterwards.getLastModified());
    }

  }

}
