package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.MongoException;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.migration.transaction.AggregateWrite;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.adapter.spi.workflowtask.MultiInstanceValue;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.adapter.spi.workflowtask.WorkflowTaskOutcome;
import io.vanillabp.integration.runtime.workflowtask.QuarkusTransactionRunner;
import io.vanillabp.integration.test.persistence.parallel.CountingCommandListener;
import io.vanillabp.integration.test.persistence.parallel.Details;
import io.vanillabp.integration.test.persistence.parallel.IterationRecord;
import io.vanillabp.integration.test.persistence.parallel.IterationRecordRepository;
import io.vanillabp.integration.test.persistence.parallel.ParallelActiveRecordAggregate;
import io.vanillabp.integration.test.persistence.parallel.ParallelActiveRecordWorkflowService;
import io.vanillabp.integration.test.persistence.parallel.ParallelProcessWiringSource;
import io.vanillabp.integration.test.persistence.parallel.ParallelRepositoryAggregate;
import io.vanillabp.integration.test.persistence.parallel.ParallelRepositoryAggregateRepository;
import io.vanillabp.integration.test.persistence.parallel.ParallelRepositoryWorkflowService;
import io.vanillabp.integration.test.persistence.parallel.Rendezvous;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test on Quarkus: a parallel multi-instance on a workflow aggregate which MongoDB
 * Panache stores, once through a repository and once as an active record. Every iteration is
 * one task delivery, and all of them arrive at the same time. Each one loads the same
 * document, and none of them goes on before all of them loaded it ({@link Rendezvous}).
 * <p>
 * Iterations which leave the aggregate unchanged get through at the first attempt. That
 * holds for iterations which only read, and for iterations which write their result into a
 * record of another collection. VanillaBP does not write a document which did not change,
 * see {@code DECISIONS.pending/989.md}. Iterations which really change the aggregate still
 * collide, and each failure gets the guiding message about a version conflict.
 * <p>
 * MongoDB Panache has no version attribute. So this platform knows only the case without
 * one. It has no reference to a document of another collection either. An aggregate points
 * to such a document by its id, and the tests at the end say which change of that counts.
 * Each of them counts the commands which update the aggregate's collection.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoParallelMultiInstanceTest {

  private static final String MODULE = "test-module";

  private static final String REPOSITORY_PROCESS = "ParallelRepositoryProcess";

  private static final String ACTIVE_RECORD_PROCESS = "ParallelActiveRecordProcess";

  private static final int ITERATIONS = 4;

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(ParallelRepositoryAggregate.class)
          .addClass(ParallelRepositoryAggregateRepository.class)
          .addClass(ParallelRepositoryWorkflowService.class)
          .addClass(ParallelActiveRecordAggregate.class)
          .addClass(ParallelActiveRecordWorkflowService.class)
          .addClass(IterationRecord.class)
          .addClass(IterationRecordRepository.class)
          .addClass(Details.class)
          .addClass(Rendezvous.class)
          .addClass(CountingCommandListener.class)
          .addClass(ParallelProcessWiringSource.class)
          .addAsResource(
              new StringAsset("not parsed by the dummy adapter"),
              "processes/dummy/"
                  + REPOSITORY_PROCESS
                  + ".bpmn")
          .addAsResource(
              new StringAsset("not parsed by the dummy adapter"),
              "processes/dummy/"
                  + ACTIVE_RECORD_PROCESS
                  + ".bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", "parallel-multi-instance-it")
      .overrideConfigKey("quarkus.mongodb.devservices.image-name", ContainerImages.MONGODB)
      .overrideConfigKey(workflowKey(REPOSITORY_PROCESS, "allow-full-sync-with-bpms"), "true")
      .overrideConfigKey(workflowKey(REPOSITORY_PROCESS, "declared-aggregate-values"), "*")
      .overrideConfigKey(workflowKey(ACTIVE_RECORD_PROCESS, "allow-full-sync-with-bpms"), "true")
      .overrideConfigKey(workflowKey(ACTIVE_RECORD_PROCESS, "declared-aggregate-values"), "*");

  private static String workflowKey(
      final String bpmnProcessId,
      final String property) {

    return "vanillabp.workflow-modules.test-module.workflows.%s.%s".formatted(bpmnProcessId, property);

  }

  @Inject
  ParallelRepositoryAggregateRepository repository;

  @Inject
  IterationRecordRepository records;

  @Inject
  Rendezvous rendezvous;

  @Inject
  QuarkusTransactionRunner transactionRunner;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

  @BeforeEach
  public void storeTheScenario() {

    rendezvous.expect(ITERATIONS);
    repository.deleteAll();
    ParallelActiveRecordAggregate.deleteAll();
    records.deleteAll();
    final var recordIds = new ArrayList<String>();
    IntStream
        .range(0, ITERATIONS)
        .forEach(index -> recordIds.add(storeRecord("record-%d".formatted(index))));
    storeRecord(ParallelRepositoryWorkflowService.OTHER_RECORD);

    final var aggregate = new ParallelRepositoryAggregate();
    aggregate.setId("aggregate");
    aggregate.setContent("as started");
    aggregate.setIterationRecordIds(new ArrayList<>(recordIds));
    aggregate.setDetails(details());
    repository.persist(aggregate);

    final var activeRecord = new ParallelActiveRecordAggregate();
    activeRecord.id = "aggregate";
    activeRecord.content = "as started";
    activeRecord.iterationRecordIds = new ArrayList<>(recordIds);
    activeRecord.details = details();
    activeRecord.persist();

  }

  @Test
  @DisplayName("Iterations which only read a repository's aggregate do not conflict")
  public void readingIterationsOfARepositoryDoNotConflict() throws Exception {

    assertGotThroughAtTheFirstAttempt(run(REPOSITORY_PROCESS, "readingIteration"));

  }

  @Test
  @DisplayName("Iterations which only read an active record do not conflict")
  public void readingIterationsOfAnActiveRecordDoNotConflict() throws Exception {

    assertGotThroughAtTheFirstAttempt(run(ACTIVE_RECORD_PROCESS, "readingIteration"));

  }

  @Test
  @DisplayName("Iterations which write records of another collection do not conflict on a repository's aggregate")
  public void recordingIterationsOfARepositoryDoNotConflict() throws Exception {

    CountingCommandListener.forgetWhatWasSent();

    assertGotThroughAtTheFirstAttempt(run(REPOSITORY_PROCESS, "recordingIteration"));

    assertEveryIterationRecorded();
    assertEquals(List.of(), updatesOf("ParallelRepositoryAggregate"));

  }

  @Test
  @DisplayName("Iterations which write records of another collection do not conflict on an active record")
  public void recordingIterationsOfAnActiveRecordDoNotConflict() throws Exception {

    CountingCommandListener.forgetWhatWasSent();

    assertGotThroughAtTheFirstAttempt(run(ACTIVE_RECORD_PROCESS, "recordingIteration"));

    assertEveryIterationRecorded();
    assertEquals(List.of(), updatesOf("ParallelActiveRecordAggregate"));

  }

  @Test
  @DisplayName("Iterations which change a repository's aggregate still collide, and are named")
  public void writingIterationsOfARepositoryCollide() throws Exception {

    assertCollided(run(REPOSITORY_PROCESS, "writingIteration"));
    assertTrue(repository
        .findById("aggregate")
        .getContent()
        .startsWith("written by iteration"));

  }

  @Test
  @DisplayName("Iterations which change an active record still collide, and are named")
  public void writingIterationsOfAnActiveRecordCollide() throws Exception {

    assertCollided(run(ACTIVE_RECORD_PROCESS, "writingIteration"));

  }

  @Test
  @DisplayName("A record changed behind an id is no change of the aggregate")
  public void aRecordChangedBehindAnIdIsNoChange() throws Exception {

    runOnceAndExpectWrites("changeRecordBehindId", 0);
    assertEquals("changed behind the id", records.findById("record-0").getResult());

  }

  @Test
  @DisplayName("An id which points to another record is a change")
  public void anIdPointingElsewhereIsAChange() throws Exception {

    runOnceAndExpectWrites("pointToAnotherRecord", 1);
    assertEquals(
        ParallelRepositoryWorkflowService.OTHER_RECORD,
        repository
            .findById("aggregate")
            .getIterationRecordIds()
            .getFirst());

  }

  @Test
  @DisplayName("A change inside a sub-document is a change")
  public void aChangedSubDocumentIsAChange() throws Exception {

    runOnceAndExpectWrites("changeSubDocument", 1);
    assertEquals(
        "changed in the sub-document",
        repository
            .findById("aggregate")
            .getDetails()
            .getNote());

  }

  /**
   * Every iteration was delivered once and completed. Nothing failed, so the BPMS has
   * nothing to deliver again, and nothing was reported.
   */
  private static void assertGotThroughAtTheFirstAttempt(
      final Run run) {

    assertEquals(List.of(), run.failures(), run.describe());
    assertEquals(ITERATIONS, run.completed(), run.describe());
    assertEquals(List.of(), run.messages(), run.describe());

  }

  /**
   * One iteration wins, every other one fails. Each failure is recognized as a conflict by
   * the platform, and so each one got the guiding message.
   */
  private void assertCollided(
      final Run run) {

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
                .getResult()));

  }

  private void runOnceAndExpectWrites(
      final String taskDefinition,
      final int writes) {

    CountingCommandListener.forgetWhatWasSent();

    final var outcome = dummyAdapter().invokeTask(MODULE, REPOSITORY_PROCESS, delivery(taskDefinition, -1));

    assertEquals(WorkflowTaskOutcome.Kind.COMPLETED, outcome.kind());
    assertEquals(writes, updatesOf("ParallelRepositoryAggregate").size());

  }

  /**
   * Panache replaces a document with an <code>update</code> command. A command which only
   * reads is something else.
   */
  private static List<String> updatesOf(
      final String collection) {

    return CountingCommandListener
        .commandsOn(collection)
        .stream()
        .filter(command -> command.startsWith("update "))
        .toList();

  }

  private String storeRecord(
      final String id) {

    final var iterationRecord = new IterationRecord();
    iterationRecord.setId(id);
    iterationRecord.setResult("none yet");
    records.persist(iterationRecord);
    return id;

  }

  private static Details details() {

    final var details = new Details();
    details.setNote("as started");
    return details;

  }

  /**
   * Delivers all iterations at the same time, each on a thread of its own, as a BPMS does
   * with a parallel multi-instance. Each delivery happens once.
   */
  private Run run(
      final String bpmnProcessId,
      final String taskDefinition) throws Exception {

    final var dummyAdapter = dummyAdapter();
    final var completed = new AtomicInteger();
    final var failures = Collections.synchronizedList(new ArrayList<Throwable>());
    final var messages = Collections.synchronizedList(new ArrayList<String>());
    final var handler = new java.util.logging.Handler() {

      @Override
      public void publish(
          final java.util.logging.LogRecord record) {
        if (record.getLevel().intValue() >= java.util.logging.Level.WARNING.intValue()) {
          messages.add(record.getMessage());
        }
      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }

    };
    final var logger = java.util.logging.Logger.getLogger(AggregateWrite.class.getName());
    logger.addHandler(handler);
    try (var executor = Executors.newFixedThreadPool(ITERATIONS)) {
      final var deliveries = IntStream
          .range(0, ITERATIONS)
          .mapToObj(index -> executor.submit(() -> {
            try {
              final var outcome = dummyAdapter
                  .invokeTask(MODULE, bpmnProcessId, delivery(taskDefinition, index));
              if (outcome.kind() == WorkflowTaskOutcome.Kind.COMPLETED) {
                completed.incrementAndGet();
              }
            } catch (final RuntimeException failure) {
              failures.add(failure);
            }
          }))
          .toList();
      for (final Future<?> delivery : deliveries) {
        delivery.get();
      }
    } finally {
      logger.removeHandler(handler);
    }
    return new Run(completed.get(), List.copyOf(failures), List.copyOf(messages));

  }

  private DummyDeploymentService dummyAdapter() {

    return deploymentServices
        .stream()
        .filter(java.util.Objects::nonNull)
        .flatMap(List::stream)
        .filter(java.util.Objects::nonNull)
        .filter(DummyDeploymentService.class::isInstance)
        .map(DummyDeploymentService.class::cast)
        .filter(service -> "demo1".equals(service.getAdapterId()))
        .findFirst()
        .orElseThrow();

  }

  /**
   * The context of one delivery. An index below zero means a task outside of a
   * multi-instance.
   */
  private static TaskInvocationContext delivery(
      final String taskDefinition,
      final int index) {

    return new TaskInvocationContext() {

      @Override
      public String getTaskDefinition() {
        return taskDefinition;
      }

      @Override
      public String getWorkflowAggregateId() {
        return "aggregate";
      }

      @Override
      public Map<String, MultiInstanceValue> getMultiInstances() {
        final var multiInstances = new LinkedHashMap<String, MultiInstanceValue>();
        if (index >= 0) {
          multiInstances.put("Iterations", new MultiInstanceValue(null, index, ITERATIONS));
        }
        return multiInstances;
      }

    };

  }

  /**
   * What came out of one run of all iterations.
   *
   * @param completed How many deliveries completed
   * @param failures What the deliveries which failed threw at the adapter
   * @param messages The warnings and errors about a version conflict
   */
  private record Run(
                     int completed,
                     List<Throwable> failures,
                     List<String> messages) {

    /**
     * Each failure with its chain of causes, the MongoDB error code where there is one, and
     * the messages. A failed assertion prints this, so a red run says what arrived.
     */
    String describe() {

      return failures
          .stream()
          .map(Run::chainOf)
          .collect(Collectors.joining("\n", "completed: %d\nfailures:\n".formatted(completed), "\nmessages: %s"
              .formatted(messages)));

    }

    private static String chainOf(
        final Throwable failure) {

      final var chain = new ArrayList<String>();
      var candidate = failure;
      while (candidate != null) {
        chain
            .add(candidate instanceof MongoException mongoException
                ? "%s(code %d, labels %s)"
                    .formatted(candidate.getClass().getName(), mongoException.getCode(), mongoException
                        .getErrorLabels())
                : candidate.getClass().getName());
        candidate = candidate.getCause() == candidate
            ? null
            : candidate.getCause();
      }
      return String.join(" <- ", chain);

    }

  }

}
