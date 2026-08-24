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
 * correlation's election probes that adapter first and keeps asking for as long as
 * the adapter says its BPMS may need. A workflow nobody ever started has no such
 * record and fails immediately.
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
  @DisplayName("Correlating right after the start succeeds although the BPMS reports the workflow late")
  public void correlationWaitsForTheWorkflowToBecomeVisible() throws Exception {

    final var aggregate = started("visibility-delay");
    // the BPMS holds the workflow but reports it as unknown for the next three
    // probes - what an exporter-fed read model does right after a start
    awareness.becomeVisibleAfter(3, Duration.ofSeconds(5));

    userTransaction.begin();
    try {
      workflowService.correlateMessage(aggregate, "PaymentReceived");
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    assertEquals(
        0, awareness.remainingInvisibleProbes(), "the core has to keep asking until the workflow shows up");

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

    assertTrue(
        elapsed.toSeconds() < 30,
        "an unknown workflow must fail without waiting, but took "
            + elapsed);

  }

}
