package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.bpmsdouble.DummyPhaseTwoListener;
import io.vanillabp.integration.adapter.spi.workflowstart.BpmsInitiatedStartContext;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.ContainerImages;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.service.BpmsStartTrigger;

/**
 * Acceptance test on Spring Boot with PostgreSQL. PostgreSQL ends a transaction at the first
 * statement which fails, and an insert which meets the key of another row is such a
 * statement. H2, MySQL, Oracle and SQL Server go on after it. So whatever VanillaBP writes
 * into the log of task deliveries inside somebody's transaction is tested here.
 * <p>
 * The first tests drive the start of a workflow which reaches two places at nearly the same
 * time: the dispatch of the outbox, and the start handler, to which a remote BPMS hands the
 * start at once. The other tests write the same row twice on purpose, where that is part of
 * the design: a second workflow of an aggregate, and a delivery which two nodes process at the
 * same time.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = {
    TestApplication.class, TwoWritersOfOneRowOnPostgresTest.TwoWritersConfiguration.class
}, properties = {
    "dummy-adapter.read-aggregate-in-phase-two=true", "spring.jpa.hibernate.ddl-auto=create"
})
@Testcontainers
public class TwoWritersOfOneRowOnPostgresTest {

  private static final String MODULE = "test-module";

  private static final String PROCESS = "SampleWorkflowService";

  private static final String ADAPTER = "test";

  private static final String OLD_WARNING = "could not be written down";

  @Container
  static PostgreSQLContainer postgres = new PostgreSQLContainer(DockerImageName.parse(ContainerImages.POSTGRESQL));

  @DynamicPropertySource
  static void theDatabase(
      final DynamicPropertyRegistry registry) {

    registry.add("spring.datasource.url", postgres::getJdbcUrl);
    registry.add("spring.datasource.username", postgres::getUsername);
    registry.add("spring.datasource.password", postgres::getPassword);

  }

  enum Order {
    NONE,
    HANDLER_FIRST,
    DISPATCH_FIRST,
    FREE_RUNNING
  }

  /**
   * Plays the BPMS: the instance gets the id <code>wf-&lt;aggregate&gt;</code>, and the start
   * job reaches the start handler at the moment the order asks for.
   */
  static class StartJobOfTheBpms implements DummyPhaseTwoListener {

    volatile Order order = Order.NONE;

    volatile DummyDeploymentService deploymentService;

    final Map<Object, Throwable> handlerFailures = new ConcurrentHashMap<>();

    final Map<Object, Boolean> handlerDone = new ConcurrentHashMap<>();

    @Override
    public void startedWorkflowPhaseTwo(
        final Object workflowAggregateId) {

      switch (order) {
        case HANDLER_FIRST -> join(callTheStartHandler(workflowAggregateId), Duration.ofSeconds(20));
        case DISPATCH_FIRST -> TransactionSynchronizationManager
            .registerSynchronization(new TransactionSynchronization() {

              @Override
              public void beforeCommit(
                  final boolean readOnly) {

                // the row of the dispatch is written, and its transaction is still open.
                // On PostgreSQL the insert of the start handler waits for that
                // transaction. So it gets a moment, and then the dispatch commits
                join(callTheStartHandler(workflowAggregateId), Duration.ofSeconds(2));

              }

            });
        case FREE_RUNNING -> callTheStartHandler(workflowAggregateId);
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
        final Object workflowAggregateId) {

      final var thread = new Thread(() -> {
        try {
          deploymentService.startWorkflowByBpms(MODULE, PROCESS, startOf(workflowAggregateId));
        } catch (final Throwable e) {
          handlerFailures.put(workflowAggregateId.toString(), e);
        } finally {
          handlerDone.put(workflowAggregateId.toString(), Boolean.TRUE);
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

    private static BpmsInitiatedStartContext startOf(
        final Object workflowAggregateId) {

      return new BpmsInitiatedStartContext() {

        @Override
        public String getStartEventId() {
          return "StartEvent";
        }

        @Override
        public BpmsStartTrigger.Kind getKind() {
          return BpmsStartTrigger.Kind.NONE;
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
    StartJobOfTheBpms startJobOfTheBpms() {
      return new StartJobOfTheBpms();
    }

  }

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private JdbcTaskDeliveryLog deliveryLog;

  @Autowired
  private DataSource dataSource;

  @Autowired
  private StartJobOfTheBpms startJob;

  @Autowired
  private ApplicationContext context;

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

    final var failures = new ArrayList<String>();
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
  @DisplayName("The start of a second workflow of an aggregate replaces the row of the first one")
  public void aSecondWorkflowReplacesTheRow() {

    final var aggregateId = "second-workflow-"
        + System.nanoTime();
    transactionTemplate
        .executeWithoutResult(status -> deliveryLog.recordWorkflowStart(startRow(aggregateId, "wf-first")));

    // the insert meets the row of the first workflow, and the update replaces it. Both run
    // in one transaction, which has to survive the insert
    final var replaced = transactionTemplate
        .execute(status -> deliveryLog.recordWorkflowStart(startRow(aggregateId, "wf-second")));

    assertTrue(replaced);
    assertEquals("wf-second", workflowIdInTheRowOf(aggregateId));

  }

  @Test
  @DisplayName("A delivery which two nodes record at the same time keeps the work of the second node")
  public void theSecondRecordOfADeliveryKeepsItsTransaction() throws Exception {

    final var deliveryKey = "two-nodes-"
        + System.nanoTime();
    final var firstWrote = new CountDownLatch(1);
    final var secondTried = new CountDownLatch(1);
    final var secondRecorded = new AtomicReference<Boolean>();
    final var secondFailure = new AtomicReference<Throwable>();

    final var firstNode = new Thread(() -> transactionTemplate.executeWithoutResult(status -> {
      deliveryLog.record(delivery(deliveryKey));
      firstWrote.countDown();
      await(secondTried);
    }), "first-node");
    final var secondNode = new Thread(() -> {
      try {
        transactionTemplate.executeWithoutResult(status -> {
          await(firstWrote);
          // the insert waits for the first node. It meets the row when the first node
          // commits, and the store answers "recorded already". The work of this node goes
          // on in the same transaction, and has to commit
          secondTried.countDown();
          secondRecorded.set(deliveryLog.record(delivery(deliveryKey)));
          deliveryLog.record(delivery(deliveryKey
              + "-own-work"));
        });
      } catch (final Throwable e) {
        secondFailure.set(e);
      }
    }, "second-node");
    secondNode.start();
    firstNode.start();
    firstNode.join(20000);
    secondNode.join(20000);

    assertNull(secondFailure.get(), () -> "the second node failed: "
        + secondFailure.get());
    assertFalse(secondRecorded.get());
    assertTrue(
        transactionTemplate
            .execute(status -> deliveryLog.recordedDelivery(deliveryKey
                + "-own-work"))
            .isPresent(),
        "the own work of the second node was rolled back");

  }

  private static void await(
      final CountDownLatch latch) {

    try {
      // the second node blocks inside its insert. So the first node gives it a moment to
      // get there, and then commits
      if (!latch.await(20, TimeUnit.SECONDS)) {
        throw new AssertionError("the other node did not get there");
      }
      Thread.sleep(500);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

  }

  private static TaskDelivery delivery(
      final String deliveryKey) {

    return new TaskDelivery(
        deliveryKey, ADAPTER, MODULE, PROCESS, "4711", null, "someTask", null, null, "COMPLETED", null, null, Instant
            .now(), null);

  }

  private static TaskDelivery startRow(
      final String aggregateId,
      final String workflowId) {

    return TaskDelivery.workflowStart(ADAPTER, MODULE, PROCESS, aggregateId, workflowId, null, Instant.now());

  }

  private String workflowIdInTheRowOf(
      final String aggregateId) {

    return transactionTemplate
        .execute(status -> deliveryLog
            .workflowStartOf(MODULE, PROCESS, aggregateId)
            .map(TaskDelivery::workflowId)
            .orElse(null));

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
      return processService.startWorkflow(aggregate).getId().toString();
    });

    final var outbox = PhaseTwoOutboxReader.ofTheVanillaBpOutbox(new TransactionAwareDataSourceProxy(dataSource));
    awaitThat(
        () -> Boolean.TRUE.equals(startJob.handlerDone.get(aggregateId)) && entriesOf(outbox, aggregateId)
            .stream()
            .allMatch(PhaseTwoOutboxReader.Entry::wasDispatched),
        "the start of "
            + aggregateId
            + " was dispatched and the start handler ran");

    final var problems = new ArrayList<String>();
    final var handlerFailure = startJob.handlerFailures.get(aggregateId);
    if (handlerFailure != null) {
      problems.add("the start handler failed: "
          + handlerFailure);
    }
    final var entries = entriesOf(outbox, aggregateId);
    if ((entries.size() != 1) || (entries.getFirst().attempts() != 1)) {
      problems.add("the dispatch took more than one attempt: "
          + entries);
    }
    final var row = workflowIdInTheRowOf(aggregateId);
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

  private static List<PhaseTwoOutboxReader.Entry> entriesOf(
      final PhaseTwoOutboxReader outbox,
      final String aggregateId) {

    return outbox
        .entries()
        .stream()
        .filter(entry -> aggregateId.equals(entry.aggregateId()))
        .toList();

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
