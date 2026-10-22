package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

import com.mongodb.ConnectionString;

import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.delivery.MongoTaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.MongoPhaseTwoOutboxReader;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Acceptance test on Spring Boot with MongoDB as a replica set, so with transactions. A
 * workflow which the application starts reaches two places at nearly the same time: the
 * dispatch of the outbox, which learns the id of the new instance, and the start handler,
 * to which a remote BPMS like Camunda 8 hands the start at once.
 * <p>
 * Both used to write the row about the start, each in its own transaction. MongoDB answers
 * the second insert of the same document inside a transaction with a write conflict, and
 * that ends the transaction of the loser. Now only the dispatch writes the row, because the
 * start handler found the workflow aggregate and built nothing. The test drives both orders
 * on purpose, plus one run where nothing waits for anything.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = {
    TestApplication.class, TwoWritersOfTheStartRowTest.TwoWritersConfiguration.class
}, properties = {
    // like Camunda 8, which reads the aggregate in phase two to build the variables. That
    // read opens the snapshot of the dispatch's transaction before the start handler runs
    "dummy-adapter.read-aggregate-in-phase-two=true"
})
@Testcontainers
public class TwoWritersOfTheStartRowTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "SampleWorkflowService";

  private static final String ADAPTER = "test";

  /**
   * The words of the old warning. It said that the row could not be written, while the other
   * writer had just written it.
   */
  private static final String OLD_WARNING = "could not be written down";

  @Container
  static MongoDBContainer mongoDb = new MongoDBContainer(DockerImageName.parse(ContainerImages.MONGODB))
      // MongoDB transactions require a replica set
      .withReplicaSet()
      .waitingFor(Wait.forLogMessage(".*Waiting for connections.*", 1))
      .withExposedPorts(27017);

  /**
   * In which order the two writers meet.
   */
  enum Order {
    /** The start handler is not called. */
    NONE,
    /** The start handler runs and commits while the dispatch is still in phase two. */
    HANDLER_FIRST,
    /** The dispatch wrote its row, and the start handler runs before the dispatch commits. */
    DISPATCH_FIRST,
    /** The start handler runs on its own thread, and nobody waits for anybody. */
    FREE_RUNNING
  }

  /**
   * Plays the BPMS: the instance gets the id <code>wf-&lt;aggregate&gt;</code>, and the start
   * job reaches the start handler at the moment the order asks for.
   */
  static class StartJobOfTheBpms implements DummyPhaseTwoListener {

    volatile Order order = Order.NONE;

    /**
     * Whether the next start goes by message. Then the double reports no id in phase two,
     * like Camunda 8, whose answer to a published message names the message and not the
     * instance.
     */
    volatile boolean byMessage;

    volatile DummyDeploymentService deploymentService;

    final Map<Object, Throwable> handlerFailures = new ConcurrentHashMap<>();

    final Map<Object, Boolean> handlerDone = new ConcurrentHashMap<>();

    @Override
    public void startedWorkflowPhaseTwo(
        final Object workflowAggregateId) {

      meet(workflowAggregateId, BpmsStartTrigger.Kind.NONE);

    }

    @Override
    public void startedWorkflowByMessagePhaseTwo(
        final Object workflowAggregateId,
        final String messageName) {

      meet(workflowAggregateId, BpmsStartTrigger.Kind.MESSAGE);

    }

    private void meet(
        final Object workflowAggregateId,
        final BpmsStartTrigger.Kind kind) {

      switch (order) {
        case HANDLER_FIRST -> join(callTheStartHandler(workflowAggregateId, kind), Duration.ofSeconds(20));
        case DISPATCH_FIRST -> TransactionSynchronizationManager
            .registerSynchronization(new TransactionSynchronization() {

              @Override
              public void beforeCommit(
                  final boolean readOnly) {

                // the row of the dispatch is written, and its transaction is still open. A
                // handler which has to wait for it would wait forever, so it gets a moment
                // and then the dispatch commits
                join(callTheStartHandler(workflowAggregateId, kind), Duration.ofSeconds(2));

              }

            });
        case FREE_RUNNING -> callTheStartHandler(workflowAggregateId, kind);
        default -> {
        }
      }

    }

    @Override
    public String workflowIdOf(
        final String adapterId,
        final Object workflowAggregateId) {

      return "wf-"
          + workflowAggregateId;

    }

    private Thread callTheStartHandler(
        final Object workflowAggregateId,
        final BpmsStartTrigger.Kind kind) {

      final var thread = new Thread(() -> {
        try {
          deploymentService.startWorkflowByBpms(MODULE, PROCESS, startOf(workflowAggregateId, kind));
        } catch (final Throwable e) {
          handlerFailures.put(workflowAggregateId, e);
        } finally {
          handlerDone.put(workflowAggregateId, Boolean.TRUE);
        }
      }, "start-job-"
          + workflowAggregateId);
      thread.start();
      return thread;

    }

    private static void join(
        final Thread thread,
        final Duration atMost) {

      try {
        thread.join(atMost.toMillis());
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
      }

    }

    /**
     * The start as Camunda 8 reports it: on a worker thread, with the instance key, and with
     * the id of the aggregate in its process variable.
     */
    private static BpmsInitiatedStartContext startOf(
        final Object workflowAggregateId,
        final BpmsStartTrigger.Kind kind) {

      return new BpmsInitiatedStartContext() {

        @Override
        public String getStartEventId() {
          return "StartEvent";
        }

        @Override
        public BpmsStartTrigger.Kind getKind() {
          return kind;
        }

        @Override
        public Map<String, Object> getVariables() {
          return Map.of("id", workflowAggregateId.toString());
        }

        @Override
        public String getNativeInstanceId() {
          return "wf-"
              + workflowAggregateId;
        }

        @Override
        public String getAdapterId() {
          return ADAPTER;
        }

      };

    }

  }

  @TestConfiguration
  static class TwoWritersConfiguration {

    @Bean
    MongoClientSettingsBuilderCustomizer mongoUriCustomizer() {
      return builder -> builder.applyConnectionString(
          new ConnectionString(mongoDb.getReplicaSetUrl()));
    }

    @Bean
    StartJobOfTheBpms startJobOfTheBpms() {
      return new StartJobOfTheBpms();
    }

    /**
     * The BPMS knows the workflows of the aggregates in {@link #RUNNING_SINCE_VERSION_ONE},
     * and no others. So an operation on such a workflow is elected, while a start of the
     * other tests is never taken for one which happened already.
     */
    @Bean
    io.vanillabp.bpmsdouble.DummyTaskAwarenessSource workflowsOfVersionOne() {
      return (
          adapterId,
          workflowAggregateId,
          taskId) -> RUNNING_SINCE_VERSION_ONE.contains(String.valueOf(workflowAggregateId))
              ? io.vanillabp.integration.adapter.spi.WorkflowAwareness.ACTIVE
              : null;
    }

  }

  /**
   * The aggregates whose workflow a version 1 started. Such a workflow has no row about its
   * start.
   */
  static final java.util.Set<String> RUNNING_SINCE_VERSION_ONE = ConcurrentHashMap.newKeySet();

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private AggregateRepository aggregateRepository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private MongoTemplate mongoTemplate;

  @Autowired
  private MongoTaskDeliveryLog deliveryLog;

  @Autowired
  private StartJobOfTheBpms startJob;

  @Autowired
  private org.springframework.context.ApplicationContext context;

  @BeforeEach
  public void setUp() {

    startJob.deploymentService = context
        .getBean("DummyAdapter_DeploymentService_test", DummyDeploymentService.class);
    startJob.order = Order.NONE;

  }

  @Test
  @DisplayName("The start handler commits first, then the dispatch writes the row")
  public void theStartHandlerCommitsFirst(
      final CapturedOutput output) {

    assertNull(startAndCheck(Order.HANDLER_FIRST, "handler-first", output));

  }

  @Test
  @DisplayName("The dispatch writes the row, then the start handler runs before the dispatch commits")
  public void theDispatchWritesFirst(
      final CapturedOutput output) {

    assertNull(startAndCheck(Order.DISPATCH_FIRST, "dispatch-first", output));

  }

  @Test
  @DisplayName("Twenty starts where nobody waits for anybody")
  public void twentyStartsWhereNobodyWaits(
      final CapturedOutput output) {

    final var failures = new java.util.ArrayList<String>();
    for (var i = 0; i < 20; ++i) {
      final var failure = startAndCheck(Order.FREE_RUNNING, "free-"
          + i, output);
      if (failure != null) {
        failures.add(failure);
      }
    }
    assertEquals(List.of(), failures, () -> failures.size()
        + " of 20 starts went wrong");

  }

  @Test
  @DisplayName("A start by message is written down by the start handler, because the dispatch learns no id")
  public void aStartByMessageIsWrittenDownByTheStartHandler(
      final CapturedOutput output) {

    startJob.order = Order.FREE_RUNNING;
    startJob.byMessage = true;
    try {
      assertNull(startAndCheck(Order.FREE_RUNNING, "by-message", output));
    } finally {
      startJob.byMessage = false;
    }

  }

  @Test
  @DisplayName("A workflow without a start row is written down where an operation found it, and never over a row")
  public void aWorkflowWhichAnOperationFoundIsWrittenDown() {

    final var outbox = MongoPhaseTwoOutboxReader.ofTheVanillaBpOutbox(mongoTemplate.getDb());

    // a workflow which version 1 started. It has an aggregate and no row about its start
    final var ofVersionOne = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("version-one");
      return aggregateRepository.save(aggregate);
    });
    RUNNING_SINCE_VERSION_ONE.add(ofVersionOne.getId());
    assertTrue(deliveryLog.workflowStartOf(MODULE, PROCESS, ofVersionOne.getId()).isEmpty());

    // the first operation does not know the id, so the adapter searches, finds it and says so
    transactionTemplate
        .executeWithoutResult(status -> processService.correlateMessage(ofVersionOne, "SomeMessage"));
    awaitThat(
        () -> outbox
            .entriesOfAggregate(ofVersionOne.getId())
            .stream()
            .allMatch(MongoPhaseTwoOutboxReader.Entry::wasDispatched),
        "the message to "
            + ofVersionOne.getId()
            + " was dispatched");
    final var written = deliveryLog.workflowStartOf(MODULE, PROCESS, ofVersionOne.getId()).orElseThrow();
    assertEquals("wf-"
        + ofVersionOne.getId(), written.workflowId());
    assertEquals(ADAPTER, written.adapterId());
    assertNull(written.processVersion());

    // a row which is there already stays as it is, also where it names another adapter
    final var withARow = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent("with-a-row");
      return aggregateRepository.save(aggregate);
    });
    RUNNING_SINCE_VERSION_ONE.add(withARow.getId());
    transactionTemplate
        .executeWithoutResult(status -> deliveryLog
            .recordWorkflowStart(
                TaskDelivery
                    .workflowStart(
                        "another-adapter",
                        MODULE,
                        PROCESS,
                        withARow.getId(),
                        "wf-of-another-adapter",
                        null,
                        Instant.now())));
    transactionTemplate.executeWithoutResult(status -> processService.correlateMessage(withARow, "SomeMessage"));
    awaitThat(
        () -> outbox
            .entriesOfAggregate(withARow.getId())
            .stream()
            .allMatch(MongoPhaseTwoOutboxReader.Entry::wasDispatched),
        "the message to "
            + withARow.getId()
            + " was dispatched");
    assertEquals(
        "wf-of-another-adapter",
        deliveryLog.workflowStartOf(MODULE, PROCESS, withARow.getId()).orElseThrow().workflowId());

  }

  /**
   * Starts one workflow and waits until both writers are through.
   *
   * @return What went wrong, or <code>null</code> where nothing did
   */
  private String startAndCheck(
      final Order order,
      final String content,
      final CapturedOutput output) {

    startJob.order = order;
    final var aggregateId = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent(content);
      return startJob.byMessage
          ? processService.startWorkflowByMessage(aggregate, "StartMessage").getId()
          : processService.startWorkflow(aggregate).getId();
    });

    final var outbox = MongoPhaseTwoOutboxReader.ofTheVanillaBpOutbox(mongoTemplate.getDb());
    awaitThat(
        () -> Boolean.TRUE.equals(startJob.handlerDone.get(aggregateId)) && outbox
            .entriesOfAggregate(aggregateId)
            .stream()
            .allMatch(MongoPhaseTwoOutboxReader.Entry::wasDispatched),
        "the start of "
            + aggregateId
            + " was dispatched and the start handler ran");

    final var problems = new java.util.ArrayList<String>();
    final var handlerFailure = startJob.handlerFailures.get(aggregateId);
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
        .workflowStartOf(MODULE, PROCESS, aggregateId)
        .map(TaskDelivery::workflowId)
        .orElse(null);
    if (!("wf-"
        + aggregateId).equals(row)) {
      problems.add("the start row names '"
          + row
          + "'");
    }
    if (output.getAllOfThisTest().contains(OLD_WARNING)) {
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
