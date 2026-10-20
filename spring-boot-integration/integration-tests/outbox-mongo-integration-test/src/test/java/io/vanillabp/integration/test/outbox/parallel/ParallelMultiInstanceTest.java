package io.vanillabp.integration.test.outbox.parallel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import java.util.stream.IntStream;

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

import io.vanillabp.integration.test.outbox.TestApplication;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.workflowtask.SpringTransactionRunner;

/**
 * Acceptance test on Spring Boot: a parallel multi-instance on a workflow aggregate stored in
 * MongoDB, under a <code>MongoTransactionManager</code>. Every iteration is one task
 * delivery, and all of them arrive at the same time. Each one loads the same document, and
 * none of them goes on before all of them loaded it ({@link Rendezvous}).
 * <p>
 * Iterations which leave the aggregate unchanged get through at the first attempt, with and without
 * a version attribute. That holds for iterations which only read, and for iterations which write
 * their result into a record of another collection. VanillaBP does not write a document which did
 * not change, see decision 130 in the repository's DECISIONS.md. Iterations which really change the
 * aggregate still collide. All but one of them fail, and each failure gets the guiding message
 * about a version conflict.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = {
    TestApplication.class, ParallelMultiInstanceTest.ParallelTestConfiguration.class
})
@Testcontainers
public class ParallelMultiInstanceTest {

  private static final int ITERATIONS = 4;

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB))
      // MongoDB transactions require a replica set
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  /**
   * Points the application to the container and switches auditing on. The auditing field
   * of {@link VersionedParallelAggregate} then changes on every write.
   */
  @TestConfiguration
  @EnableMongoAuditing
  static class ParallelTestConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {
      return builder -> builder.applyConnectionString(
          new ConnectionString(mongoDb.getReplicaSetUrl()));
    }

  }

  @Autowired
  private ParallelIterations iterations;

  @Autowired
  private ParallelScenario scenario;

  @Autowired
  private ParallelAggregateRepository repository;

  @Autowired
  private VersionedParallelAggregateRepository versionedRepository;

  @Autowired
  private IterationRecordRepository records;

  @Autowired
  private SpringTransactionRunner transactionRunner;

  @Autowired
  private Rendezvous rendezvous;

  @BeforeEach
  public void expectAllIterations() {

    rendezvous.expect(ITERATIONS);

  }

  @Test
  @DisplayName("Iterations which only read an aggregate without a version do not conflict")
  public void readingIterationsWithoutVersionDoNotConflict() throws Exception {

    scenario.store(new ParallelAggregate(), ITERATIONS);

    final var run = iterations.run("ParallelProcess", "readingIteration", ITERATIONS);

    assertGotThroughAtTheFirstAttempt(run);
    assertEquals(
        "as started",
        repository
            .findById("aggregate")
            .orElseThrow()
            .getContent());

  }

  @Test
  @DisplayName("Iterations which only read a versioned aggregate do not conflict and do not write")
  public void readingIterationsWithVersionDoNotConflict() throws Exception {

    final var stored = scenario.store(new VersionedParallelAggregate(), ITERATIONS);

    final var run = iterations.run("VersionedParallelProcess", "readingIteration", ITERATIONS);

    assertGotThroughAtTheFirstAttempt(run);
    assertNothingWritten(stored);

  }

  @Test
  @DisplayName("Iterations which write records of another collection do not conflict on an aggregate without a version")
  public void recordingIterationsWithoutVersionDoNotConflict() throws Exception {

    scenario.store(new ParallelAggregate(), ITERATIONS);

    final var run = iterations.run("ParallelProcess", "recordingIteration", ITERATIONS);

    assertGotThroughAtTheFirstAttempt(run);
    assertEveryIterationRecorded();

  }

  @Test
  @DisplayName("Iterations which write records of another collection do not conflict on a versioned aggregate")
  public void recordingIterationsWithVersionDoNotConflict() throws Exception {

    final var stored = scenario.store(new VersionedParallelAggregate(), ITERATIONS);

    final var run = iterations.run("VersionedParallelProcess", "recordingIteration", ITERATIONS);

    assertGotThroughAtTheFirstAttempt(run);
    assertEveryIterationRecorded();
    assertNothingWritten(stored);

  }

  @Test
  @DisplayName("Iterations which change an aggregate without a version still collide, and are named")
  public void writingIterationsWithoutVersionCollide() throws Exception {

    scenario.store(new ParallelAggregate(), ITERATIONS);

    final var run = iterations.run("ParallelProcess", "writingIteration", ITERATIONS);

    assertCollided(run);
    assertTrue(
        repository
            .findById("aggregate")
            .orElseThrow()
            .getContent()
            .startsWith("written by iteration"));

  }

  @Test
  @DisplayName("Iterations which change a versioned aggregate still collide, and are named")
  public void writingIterationsWithVersionCollide() throws Exception {

    final var stored = scenario.store(new VersionedParallelAggregate(), ITERATIONS);

    final var run = iterations.run("VersionedParallelProcess", "writingIteration", ITERATIONS);

    assertCollided(run);
    // the one iteration which got through wrote, so the version moved by one
    assertEquals(
        stored.getVersion() + 1,
        versionedRepository
            .findById("aggregate")
            .orElseThrow()
            .getVersion());

  }

  /**
   * Every iteration was delivered once and completed. Nothing failed, so the BPMS has
   * nothing to deliver again, and nothing was reported.
   */
  private static void assertGotThroughAtTheFirstAttempt(
      final ParallelIterations.Run run) {

    assertEquals(List.of(), run.failures(), run.describe());
    assertEquals(ITERATIONS, run.completed(), run.describe());
    assertEquals(List.of(), run.messages(), run.describe());

  }

  /**
   * One iteration wins, every other one fails. Each failure is recognized as a conflict by
   * the platform, and so each one got the guiding message.
   */
  private void assertCollided(
      final ParallelIterations.Run run) {

    assertEquals(ITERATIONS - 1, run.failures().size(), run.describe());
    assertEquals(1, run.completed(), run.describe());
    assertEquals(
        Collections.nCopies(ITERATIONS - 1, true),
        run
            .failures()
            .stream()
            .map(transactionRunner::isConcurrentModification)
            .toList(),
        run.describe());
    assertEquals(ITERATIONS - 1, run.messages().size(), run.describe());
    run
        .messages()
        .forEach(message -> assertTrue(message.contains("version conflict"), message));

  }

  private void assertEveryIterationRecorded() {

    IntStream
        .range(0, ITERATIONS)
        .forEach(index -> assertEquals(
            "result of iteration %d".formatted(index),
            records
                .findById("record-%d".formatted(index))
                .orElseThrow()
                .getResult()));

  }

  /**
   * Neither the version nor the auditing field moved. Spring Data sets both on every write,
   * so the document was not written at all.
   */
  private void assertNothingWritten(
      final VersionedParallelAggregate stored) {

    final var afterwards = versionedRepository
        .findById("aggregate")
        .orElseThrow();
    assertEquals(stored.getVersion(), afterwards.getVersion());
    assertEquals(stored.getLastModified(), afterwards.getLastModified());

  }

}
