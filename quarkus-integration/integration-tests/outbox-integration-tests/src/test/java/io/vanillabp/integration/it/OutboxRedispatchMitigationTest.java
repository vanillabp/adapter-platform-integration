package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.PerAdapterAwarenessSource;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * The START re-dispatch mitigation on the Quarkus JDBC outbox (story 25): the
 * dispatcher's claim increments the ATTEMPTS column BEFORE dispatching, so a
 * retried entry is recognized (attempts &gt; 0 before the claim) and the recorded
 * adapter's {@code awarenessOfWorkflowForRedispatch} is probed first - a workflow
 * already known consumes the entry WITHOUT a second start. The residual
 * at-least-once window is accepted and documented; this proves the mitigation.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxRedispatchMitigationTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addAsResource("redispatch-mitigation.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(PerAdapterAwarenessSource.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  PerAdapterAwarenessSource awareness;

  @Inject
  UserTransaction userTransaction;

  @Test
  @DisplayName("A retried START entry whose workflow is already known is consumed without a second start")
  public void retriedStartEntryDoesNotStartASecondWorkflow() throws Exception {

    listener.reset();
    awareness.reset();

    // the adapter reports the workflow as already known - like a crash/failure
    // right after a successful start whose state is visible on retry
    awareness.answerFor("test", WorkflowAwareness.ACTIVE);

    // the FIRST dispatch fails (BPMS "unreachable") - the entry stays pending,
    // its ATTEMPTS column now carries the failed attempt
    listener.failNextDispatches(1);

    userTransaction.begin();
    try {
      workflowService.startWorkflow("redispatch-mitigation");
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    // the first (failing) dispatch happened - previouslyAttempted was false, so
    // NO mitigation probe ran up to now
    listener.awaitInvocations(1, 10000);
    assertEquals(0, awareness.countProbesOf("test"), "the first dispatch must not probe");

    // the RETRY recognizes attempts > 0, probes the adapter (ACTIVE) and consumes
    // the entry without a second start
    final var deadline = System.currentTimeMillis() + 15000;
    while ((awareness.countProbesOf("test") == 0) && (System.currentTimeMillis() < deadline)) {
      Thread.sleep(50);
    }
    assertTrue(
        awareness.countProbesOf("test") > 0,
        "the retried entry must probe the recorded adapter's workflow awareness");

    // give the dispatcher time for further (wrong) dispatches, then assert the
    // phase two never reached the adapter again
    Thread.sleep(2000);
    assertEquals(
        1,
        listener.getInvocations().size(),
        "the mitigated retry must not start a second workflow; invocations: "
            + listener.getInvocations());

  }

}
