package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

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
 * Story 54: operating on a workflow which was started moments ago, on a BPMS whose
 * awareness probe reads an eventually consistent model.
 * <p>
 * The everyday sequence is "start a workflow, then correlate the message which lets
 * it continue". Phase two of the start records which adapter created the instance,
 * so the correlation's election probes that adapter first and keeps asking for as
 * long as the adapter says its BPMS may need. A workflow nobody ever started has no
 * such record and still fails immediately.
 */
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class WorkflowVisibilityDelayTest {

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
   * and an operation in the next transaction is exactly the case this story is
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
  @DisplayName("Correlating right after the start succeeds although the BPMS reports the workflow late")
  public void correlationWaitsForTheWorkflowToBecomeVisible() throws Exception {

    final var aggregate = started("visibility-delay");
    // the BPMS holds the workflow but reports it as unknown for the next three
    // probes - what an exporter-fed read model does right after a start
    awareness.becomeVisibleAfter(3, Duration.ofSeconds(5));

    transactionTemplate.executeWithoutResult(
        status -> processService.correlateMessage(aggregate, "PaymentReceived"));

    assertEquals(
        0, awareness.remainingInvisibleProbes(), "the core has to keep asking until the workflow shows up");

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

    assertTrue(
        elapsed.toSeconds() < 30,
        "an unknown workflow must fail without waiting, but took "
            + elapsed);
    // the message names the cause which applies on an eventually consistent BPMS
    assertTrue(
        exception.getMessage().contains("searchable"),
        () -> exception.getMessage());

  }

}
