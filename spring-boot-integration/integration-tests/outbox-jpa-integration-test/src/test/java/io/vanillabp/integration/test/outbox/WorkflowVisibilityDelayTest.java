package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;
import io.vanillabp.spi.process.WorkflowNotFoundException;

/**
 * Operating on a workflow which was started moments ago, on a BPMS whose
 * awareness probe reads an eventually consistent model.
 * <p>
 * The everyday sequence is "start a workflow, then correlate the message which lets
 * it continue". Phase two of the start records which adapter created the instance, so
 * the correlation's election probes that adapter first - and where that adapter does
 * not report the workflow yet, the correlation is PLANNED rather than waited for: the
 * caller's transaction is not the place to sit out a read model. The dispatch asks
 * again, and where the answer is still no it hands the entry back with a due time instead
 * of holding its thread. A workflow nobody ever started has no such record and
 * still fails immediately, inside the call.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = TestApplication.class)
public class WorkflowVisibilityDelayTest {

  /**
   * How many probes report the workflow as not visible yet in the test below. Three, so
   * that one probe used up and three probes left over are two different numbers.
   */
  private static final int INVISIBLE_PROBES = 3;

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

  @BeforeEach
  public void reset() {

    listener.reset();
    awareness.alwaysVisible();
    awareness.answerWith(WorkflowAwareness.ACTIVE);

  }

  /**
   * The window and the "invisible for the next N probes" counter are state of a bean in
   * the CACHED Spring context, which every test class of this module shares. Leaving them
   * behind made every workflow probe of the class running next answer "unknown" and wait
   * five minutes for nothing - three tests of {@code TaskOperationsDispatchTest} timed out
   * that way in the GitHub build, where the classes run in a different order than locally.
   */
  @AfterEach
  public void leaveNoWindowBehind() {

    awareness.alwaysVisible();

  }

  /**
   * Starts a workflow and returns right after the commit, deliberately WITHOUT
   * waiting for phase two: on a remote BPMS the instance is created asynchronously,
   * and an operation in the next transaction is exactly the case the visibility delay is
   * about. Scheduling the start already records which adapter holds the workflow.
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
    // the BPMS holds the workflow but reports it as unknown for the next three
    // probes - what an exporter-fed read model does right after a start
    awareness.becomeVisibleAfter(INVISIBLE_PROBES, Duration.ofSeconds(5));

    // the number of probes left over is read INSIDE the transaction, because the dispatch
    // begins right after the commit and probes as well
    final var probesLeftByPhaseOne = new AtomicInteger();
    transactionTemplate.executeWithoutResult(status -> {
      processService.correlateMessage(aggregate, "PaymentReceived");
      probesLeftByPhaseOne.set(awareness.remainingInvisibleProbes());
    });

    // the point of the story: the caller's transaction holds a database connection
    // and the locks on the aggregate, so nothing sleeps in it. Waiting for the BPMS
    // means asking again every twenty milliseconds, so the probes phase one used up
    // are what says whether it waited. The seconds the call took would not: a machine
    // carrying several builds leaves this JVM without a turn for seconds at a time, and
    // a wall clock cannot tell that apart from a caller sitting out the window
    assertEquals(
        INVISIBLE_PROBES - 1,
        probesLeftByPhaseOne.get(),
        "phase one asks once and leaves asking again to the dispatch");

    final var deadline = System.currentTimeMillis() + 10000;
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
  @DisplayName("A workflow nobody started fails immediately - the window is not waited out")
  public void unknownWorkflowStillFailsFast() throws Exception {

    // an aggregate which was never handed to startWorkflow: no adapter was ever
    // recorded for it, so there is nothing to wait for
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

    // thirty seconds against a window of five minutes: the call is either refused at once
    // or it sits out the window, and nothing a loaded machine does to this JVM falls
    // between the two. A tighter number would only turn a stalled JVM into a red test
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
