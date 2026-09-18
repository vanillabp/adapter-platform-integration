package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.SteerableTaskAwarenessSource;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.WorkflowNotFoundException;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * The visibility delay on Quarkus: correlating a message right after the start, on a BPMS whose
 * awareness probe reads an eventually consistent model.
 * <p>
 * Phase two of the start records which adapter created the instance, so the
 * correlation's election probes that adapter first - and where that adapter does not
 * report the workflow yet, the correlation is PLANNED rather than waited for: the
 * caller's transaction is not the place to sit out a read model. The dispatch asks
 * again, and where the answer is still no it hands the entry back with a due time instead
 * of holding its thread. A workflow nobody ever started has no such record and
 * fails immediately, inside the call.
 */
@ExtendWith(SuppressOutputExtension.class)
public class WorkflowVisibilityDelayTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(SteerableTaskAwarenessSource.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:workflow-visibility-delay-it;DB_CLOSE_DELAY=-1");

  /**
   * How many probes report the workflow as not visible yet in the test below. Three, so
   * that one probe used up and three probes left over are two different numbers.
   */
  private static final int INVISIBLE_PROBES = 3;

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  SteerableTaskAwarenessSource awareness;

  @Inject
  UserTransaction userTransaction;

  @BeforeEach
  public void reset() {

    listener.reset();
    awareness.alwaysVisible();
    awareness.answerWith(WorkflowAwareness.ACTIVE);

  }

  /**
   * Starts a workflow and returns right after the commit, deliberately WITHOUT
   * waiting for phase two: on a remote BPMS the instance is created asynchronously,
   * and an operation in the next transaction is exactly the case the visibility delay
   * is for. Scheduling the start already records which adapter holds the workflow.
   */
  private Aggregate started(
      final String content) throws Exception {

    userTransaction.begin();
    try {
      final var aggregate = workflowService.startWorkflow(content);
      userTransaction.commit();
      return aggregate;
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

  }

  @Test
  @DisplayName("Correlating right after the start returns at once and is dispatched once the BPMS caught up")
  public void correlationIsPlannedAndDispatchedWhenTheWorkflowShowsUp() throws Exception {

    final var aggregate = started("visibility-delay");
    // the BPMS holds the workflow but reports it as unknown for the next three
    // probes - what an exporter-fed read model does right after a start. Each dispatch
    // asks once and gives the entry back due in the window, so three probes are three
    // attempts: the window is what decides how long the correlation takes
    awareness.becomeVisibleAfter(INVISIBLE_PROBES, Duration.ofSeconds(1));

    // the number of probes left over is read INSIDE the transaction, because the dispatch
    // begins right after the commit and probes as well
    final int probesLeftByPhaseOne;
    userTransaction.begin();
    try {
      workflowService.correlateMessage(aggregate, "PaymentReceived");
      probesLeftByPhaseOne = awareness.remainingInvisibleProbes();
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    // the point of the story: the caller's transaction holds a database connection
    // and the locks on the aggregate, so nothing sleeps in it. Waiting for the BPMS
    // means asking again every twenty milliseconds, so the probes phase one used up
    // are what says whether it waited. The seconds the call took would not: a machine
    // carrying several builds leaves this JVM without a turn for seconds at a time, and
    // a wall clock cannot tell that apart from a caller sitting out the window
    assertEquals(
        INVISIBLE_PROBES - 1,
        probesLeftByPhaseOne,
        "phase one asks once and leaves asking again to the dispatch");

    final var deadline = System.currentTimeMillis() + 30_000;
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

    // never handed to startWorkflow, so no adapter was ever recorded for it
    final var aggregate = new Aggregate();
    aggregate.setId(-4711L);
    aggregate.setContent("never-started");
    awareness.becomeVisibleAfter(Integer.MAX_VALUE, Duration.ofMinutes(5));

    final var startedAt = System.nanoTime();
    userTransaction.begin();
    try {
      final var exception = assertThrows(
          WorkflowNotFoundException.class,
          () -> workflowService.correlateMessage(aggregate, "PaymentReceived"));
      // the message names the cause which applies on an eventually consistent BPMS
      assertTrue(
          exception.getMessage().contains("searchable"),
          exception::getMessage);
    } finally {
      userTransaction.rollback();
    }
    final var elapsed = Duration.ofNanos(System.nanoTime() - startedAt);

    // thirty seconds against a window of five minutes: the call is either refused at once
    // or it sits out the window, and nothing a loaded machine does to this JVM falls
    // between the two. A tighter number would only turn a stalled JVM into a red test
    assertTrue(
        elapsed.toSeconds() < 30,
        "an unknown workflow must fail without waiting, but took "
            + elapsed);

  }

}
