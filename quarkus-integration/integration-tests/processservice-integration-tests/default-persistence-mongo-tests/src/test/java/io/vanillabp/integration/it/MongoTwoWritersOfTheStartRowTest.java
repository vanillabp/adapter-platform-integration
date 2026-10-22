package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.spi.AdapterDeploymentService;
import io.vanillabp.integration.runtime.delivery.MongoTaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.persistence.ActiveTaskAwarenessSource;
import io.vanillabp.integration.test.persistence.MongoRepositoryAggregate;
import io.vanillabp.integration.test.persistence.MongoRepositoryAggregateRepository;
import io.vanillabp.integration.test.persistence.MongoRepositoryWorkflowService;
import io.vanillabp.integration.test.persistence.SingleTaskWiringSource;
import io.vanillabp.integration.test.persistence.StartJobOfTheBpms;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.MongoPhaseTwoOutboxReader;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

/**
 * Acceptance test on Quarkus with MongoDB Panache and transactions. A workflow which the
 * application starts reaches two places at nearly the same time: the dispatch of the outbox,
 * which learns the id of the new instance, and the start handler, to which a remote BPMS like
 * Camunda 8 hands the start at once.
 * <p>
 * Both used to write the row about the start, each in its own transaction. MongoDB answers
 * the second insert of the same document inside a transaction with a write conflict, and that
 * ends the transaction of the loser. Now only the dispatch writes the row, because the start
 * handler found the workflow aggregate and built nothing. The test drives both orders on
 * purpose, plus starts where nothing waits for anything.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoTwoWritersOfTheStartRowTest {

  private static final String DATABASE = "two-writers-it";

  private static final String OLD_WARNING = "could not be written down";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(MongoRepositoryAggregate.class)
          .addClass(MongoRepositoryAggregateRepository.class)
          .addClass(MongoRepositoryWorkflowService.class)
          .addClass(StartJobOfTheBpms.class)
          .addClass(ActiveTaskAwarenessSource.class)
          .addClass(SingleTaskWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TestProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // like Camunda 8, which reads the aggregate in phase two to build the variables. That
      // read opens the snapshot of the dispatch's transaction before the start handler runs
      .overrideConfigKey("dummy-adapter.read-aggregate-in-phase-two", "true")
      .overrideConfigKey("quarkus.mongodb.database", DATABASE)
      .overrideConfigKey("quarkus.mongodb.devservices.image-name", ContainerImages.MONGODB)
      .overrideConfigKey("vanillabp.outbox.poll-interval", "PT0.5S")
      .overrideConfigKey("vanillabp.outbox.attempt-frequency", "PT0.5S");

  /**
   * Counts the old warning. It said that the row could not be written, while the other
   * writer had just written it.
   */
  private static final java.util.concurrent.atomic.AtomicInteger OLD_WARNINGS = new java.util.concurrent.atomic.AtomicInteger();

  private static final java.util.logging.Handler OLD_WARNING_COUNTER = new java.util.logging.Handler() {

    @Override
    public void publish(
        final java.util.logging.LogRecord record) {

      if (String.valueOf(record.getMessage()).contains(OLD_WARNING)) {
        OLD_WARNINGS.incrementAndGet();
      }

    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
    }

  };

  static {
    java.util.logging.LogManager.getLogManager().getLogger("").addHandler(OLD_WARNING_COUNTER);
  }

  @org.junit.jupiter.api.AfterAll
  public static void removeTheCounter() {

    java.util.logging.LogManager.getLogManager().getLogger("").removeHandler(OLD_WARNING_COUNTER);

  }

  @Inject
  MongoRepositoryWorkflowService workflowService;

  @Inject
  StartJobOfTheBpms startJob;

  @Inject
  MongoTaskDeliveryLog deliveryLog;

  @Inject
  MongoClient mongoClient;

  @Inject
  @Any
  Instance<List<AdapterDeploymentService<Object, Object>>> deploymentServices;

  @AfterEach
  public void noMoreStartJobs() {

    startJob.meetIn(StartJobOfTheBpms.Order.NONE, null);

  }

  @Test
  @DisplayName("The start handler commits first, then the dispatch writes the row")
  public void theStartHandlerCommitsFirst() {

    assertNull(startAndCheck(StartJobOfTheBpms.Order.HANDLER_FIRST, "handler-first"));

  }

  @Test
  @DisplayName("The dispatch writes the row, then the start handler runs before the dispatch commits")
  public void theDispatchWritesFirst() {

    assertNull(startAndCheck(StartJobOfTheBpms.Order.DISPATCH_FIRST, "dispatch-first"));

  }

  @Test
  @DisplayName("Twenty starts where nobody waits for anybody")
  public void twentyStartsWhereNobodyWaits() {

    final var failures = new ArrayList<String>();
    for (var i = 0; i < 20; ++i) {
      final var failure = startAndCheck(StartJobOfTheBpms.Order.FREE_RUNNING, "free-"
          + i);
      if (failure != null) {
        failures.add(failure);
      }
    }
    assertEquals(List.of(), failures, () -> failures.size()
        + " of 20 starts went wrong");

  }

  /**
   * Starts one workflow and waits until both writers are through.
   *
   * @return What went wrong, or <code>null</code> where nothing did
   */
  private String startAndCheck(
      final StartJobOfTheBpms.Order order,
      final String aggregateId) {

    startJob.meetIn(order, theDoubleOfTheBpms());
    QuarkusTransaction
        .requiringNew()
        .run(() -> workflowService.startWorkflow(aggregateId));

    final var outbox = MongoPhaseTwoOutboxReader.ofTheVanillaBpOutbox(mongoClient.getDatabase(DATABASE));
    awaitThat(
        () -> startJob.handlerIsDone(aggregateId) && !outbox.entriesOfAggregate(aggregateId).isEmpty() && outbox
            .entriesOfAggregate(aggregateId)
            .stream()
            .allMatch(MongoPhaseTwoOutboxReader.Entry::wasDispatched),
        "the start of "
            + aggregateId
            + " was dispatched and the start handler ran");

    final var problems = new ArrayList<String>();
    final var handlerFailure = startJob.handlerFailureOf(aggregateId);
    if (handlerFailure != null) {
      problems.add("the start handler failed: "
          + handlerFailure);
    }
    final var entries = outbox.entriesOfAggregate(aggregateId);
    if ((entries.size() != 1) || (entries.getFirst().attempts() != 1)) {
      problems.add("the dispatch took more than one attempt: "
          + entries);
    }
    final var row = deliveryLog
        .workflowStartOf("test-module", "TestProcess", aggregateId)
        .map(TaskDelivery::workflowId)
        .orElse(null);
    if (!("wf-"
        + aggregateId).equals(row)) {
      problems.add("the start row names '"
          + row
          + "'");
    }
    if (OLD_WARNINGS.getAndSet(0) > 0) {
      problems.add("a writer warned about a row the other one wrote");
    }
    return problems.isEmpty()
        ? null
        : order
            + " "
            + aggregateId
            + ": "
            + String.join("; ", problems);

  }

  private DummyDeploymentService theDoubleOfTheBpms() {

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

  private static void awaitThat(
      final BooleanSupplier condition,
      final String what) {

    final var deadline = Instant.now().plusSeconds(30);
    while (!condition.getAsBoolean()) {
      if (Instant.now().isAfter(deadline)) {
        throw new AssertionError("waited 30 s in vain until "
            + what);
      }
      try {
        Thread.sleep(50);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting until "
            + what);
      }
    }

  }

}
