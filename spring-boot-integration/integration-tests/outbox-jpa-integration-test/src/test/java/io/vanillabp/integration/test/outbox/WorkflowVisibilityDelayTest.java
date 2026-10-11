package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.spi.WorkflowAdapterCache;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.WorkflowNotFoundException;

/**
 * Working on a workflow which was started moments ago, on a BPMS whose awareness probe
 * reads an eventually consistent model.
 * <p>
 * The everyday sequence is "start a workflow, then correlate the message which lets it
 * continue". Phase two of the start records which adapter created the instance. So the
 * election of the correlation probes that adapter first. If that adapter does not report
 * the workflow yet, the correlation is PLANNED, not waited for. The transaction of the
 * caller is not the place to wait for a read model. The dispatch asks again. If the
 * answer is still no, it hands the entry back with a due time and does not hold its
 * thread. A workflow nobody ever started has no such record. It still fails at once,
 * inside the call.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = TestApplication.class)
public class WorkflowVisibilityDelayTest {

  /**
   * How many probes report the workflow as not visible yet in the test below. Three, so
   * that "one probe used up" and "three probes left over" are different numbers.
   */
  private static final int INVISIBLE_PROBES = 3;

  /**
   * The window the awareness double reports while the workflow is not visible yet. Every
   * probe which answers "unknown" makes the dispatch hand the entry back, due one window
   * later.
   */
  private static final Duration VISIBILITY_WINDOW = Duration.ofSeconds(5);

  /**
   * What the case below costs on an idle machine. Phase one uses the first probe. Each
   * probe left over costs the dispatch one more window.
   */
  private static final Duration WINDOWS_THE_CASE_COSTS = VISIBILITY_WINDOW
      .multipliedBy(INVISIBLE_PROBES - 1);

  /**
   * How long a test waits for a dispatch before it says that none is coming. It is three
   * times what the case costs, and never less than thirty seconds. A machine which runs
   * other builds may leave this JVM without a turn for seconds. Such a pause does not get
   * shorter when the window does. Nothing is measured here, so only a failing test uses up
   * the budget. A passing test stops waiting as soon as the correlation arrives.
   */
  private static final long UNTIL_A_DISPATCH_COUNTS_AS_LOST = Math
      .max(30000, WINDOWS_THE_CASE_COSTS.multipliedBy(3).toMillis());

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @Autowired
  private SteerableTaskAwarenessSource awareness;

  @Autowired
  private AggregateRepository repository;

  @Autowired
  private JdbcTaskDeliveryLog deliveryLog;

  @Autowired
  private WorkflowAdapterCache cache;

  @Autowired
  private DataSource dataSource;

  @BeforeEach
  public void reset() {

    listener.reset();
    awareness.alwaysVisible();
    awareness.answerWith(WorkflowAwareness.ACTIVE);

  }

  /**
   * The window and the "invisible for the next N probes" counter are state of a bean in
   * the CACHED Spring context. Every test class of this module shares that context. Once
   * this state was left behind. Then every workflow probe of the next class answered
   * "unknown" and waited five minutes for nothing. Three tests of
   * {@code TaskOperationsDispatchTest} timed out that way in the GitHub build, where the
   * classes run in a different order than locally.
   */
  @AfterEach
  public void leaveNoWindowBehind() {

    awareness.alwaysVisible();

  }

  /**
   * Starts a workflow and returns right after the commit. On purpose it does NOT wait for
   * phase two. On a remote BPMS the instance is created asynchronously. An operation in the
   * next transaction is exactly the case of the visibility delay. Scheduling the start
   * already records which adapter holds the workflow.
   */
  private Aggregate started(
      final String content) {

    final var aggregate = transactionTemplate.execute(status -> {
      final var created = new Aggregate();
      created.setContent(content);
      return processService.startWorkflow(created);
    });
    assertNotNull(aggregate);
    return aggregate;

  }

  @Test
  @DisplayName("Correlating right after the start returns at once and is dispatched once the BPMS caught up")
  public void correlationIsPlannedAndDispatchedWhenTheWorkflowShowsUp() throws Exception {

    final var aggregate = started("visibility-delay");
    // the BPMS holds the workflow but reports it as unknown for the next three probes. A
    // read model fed by an exporter does this right after a start
    awareness.becomeVisibleAfter(INVISIBLE_PROBES, VISIBILITY_WINDOW);

    // the number of probes left over is read INSIDE the transaction. The dispatch begins
    // right after the commit, and it probes as well
    final var probesLeftByPhaseOne = new AtomicInteger();
    transactionTemplate.executeWithoutResult(status -> {
      processService.correlateMessage(aggregate, "PaymentReceived");
      probesLeftByPhaseOne.set(awareness.remainingInvisibleProbes());
    });

    // the point of the story: the transaction of the caller holds a database connection
    // and the locks on the aggregate. So nothing may sleep in it. Waiting for the BPMS
    // means asking again every twenty milliseconds. So the probes phase one used up show
    // whether it waited. The seconds the call took would not show it. A machine with
    // several builds may leave this JVM without a turn for seconds, and a wall clock
    // cannot tell that from a caller who waits out the window
    assertEquals(
        INVISIBLE_PROBES - 1,
        probesLeftByPhaseOne.get(),
        "phase one asks once and leaves asking again to the dispatch");

    // each probe which still reports the workflow as unknown costs the window the adapter
    // names. Two of the three are left for the dispatch. So this guard must span two of
    // those windows plus whatever a loaded machine adds. It measures nothing: the test
    // asserts that the correlation arrives, not how soon
    final var deadline = System.currentTimeMillis() + UNTIL_A_DISPATCH_COUNTS_AS_LOST;
    while (listener.getCorrelatedMessages().isEmpty()) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the correlation was not dispatched in time");
      Thread.sleep(50);
    }
    assertTrue(listener
        .getCorrelatedMessages()
        .contains(aggregate.getId()
            + ":PaymentReceived:null"));

  }

  @Test
  @DisplayName("After a restart the start row stands in for the lost hint, and the correlation waits for the workflow")
  public void anOperationAfterARestartWaitsForTheWorkflow() throws Exception {

    final var aggregate = started("after-a-restart");
    listener.awaitInvocations(1, UNTIL_A_DISPATCH_COUNTS_AS_LOST);
    final var start = PhaseTwoOutboxReader
        .ofTheVanillaBpOutbox(dataSource)
        .entries()
        .stream()
        .filter(entry -> PhaseOperation.START_WORKFLOW.name().equals(entry.operation()))
        .filter(entry -> aggregate.getId().toString().equals(entry.aggregateId()))
        .findFirst()
        .orElseThrow();
    // what a restarted node has: no hint in memory, and in the database the row written
    // when the workflow started. The dummy adapter reports no workflow id. So the test
    // writes the row here, as an adapter which reports one would leave it behind
    cache.invalidate(start.workflowModuleId(), start.bpmnProcessId(), start.aggregateId());
    transactionTemplate.executeWithoutResult(status -> deliveryLog
        .recordWorkflowStart(
            TaskDelivery
                .workflowStart(
                    "test",
                    start.workflowModuleId(),
                    start.bpmnProcessId(),
                    start.aggregateId(),
                    "workflow-of-"
                        + start.aggregateId(),
                    null,
                    Instant.now())));
    awareness.becomeVisibleAfter(INVISIBLE_PROBES, VISIBILITY_WINDOW);

    // without the row this is the WorkflowNotFoundException of the test below
    transactionTemplate.executeWithoutResult(
        status -> processService.correlateMessage(aggregate, "PaymentReceived"));

    final var deadline = System.currentTimeMillis() + UNTIL_A_DISPATCH_COUNTS_AS_LOST;
    while (listener.getCorrelatedMessages().isEmpty()) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the correlation was not dispatched in time, or it was consumed as stale");
      Thread.sleep(50);
    }
    assertTrue(listener
        .getCorrelatedMessages()
        .contains(aggregate.getId()
            + ":PaymentReceived:null"));

  }

  @Test
  @DisplayName("A workflow nobody started fails immediately - the window is not waited out")
  public void unknownWorkflowStillFailsFast() throws Exception {

    // an aggregate which never went to startWorkflow. No adapter was ever recorded for
    // it, so there is nothing to wait for
    final var aggregate = transactionTemplate.execute(status -> {
      final var created = new Aggregate();
      created.setContent("never-started");
      return repository.save(created);
    });
    assertNotNull(aggregate);
    awareness.becomeVisibleAfter(Integer.MAX_VALUE, Duration.ofMinutes(5));

    final var startedAt = System.nanoTime();
    final var exception = assertThrowsExactly(
        WorkflowNotFoundException.class,
        () -> transactionTemplate.executeWithoutResult(
            status -> processService.correlateMessage(aggregate, "PaymentReceived")));
    final var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    // thirty seconds against a window of five minutes. The call is either refused at once,
    // or it waits out the window. Nothing a loaded machine does to this JVM falls between
    // the two. A tighter number would only turn a stalled JVM into a red test
    assertTrue(
        elapsed.toSeconds() < 30,
        "an unknown workflow must fail without waiting, but took "
            + elapsed);
    // the message names the cause which applies on an eventually consistent BPMS
    assertTrue(
        exception.getMessage().contains("searchable"),
        exception::getMessage);

  }

}
