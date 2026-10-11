package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;

import javax.sql.DataSource;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.runtime.delivery.JdbcTaskDeliveryLog;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.spi.TaskDelivery;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.PerAdapterAwarenessSource;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * How the Quarkus JDBC outbox avoids a second START when it dispatches an entry again. It
 * recognizes an entry which a dispatch has had before: its ATTEMPTS carry an attempt which
 * ended, or its LEASED_BY carries the node which disappeared in the middle of one. Then it
 * first probes {@code awarenessOfWorkflowForRedispatch} of the recorded adapter. If the
 * workflow is already known, the entry is used up WITHOUT a second start. A small window
 * for at-least-once delivery remains; it is accepted and documented. This test proves the
 * mitigation.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxRedispatchMitigationTest {

  /**
   * How long a test waits before it says that nothing more happened. The application
   * dispatches every <code>vanillabp.outbox.attempt-frequency</code>, which these tests
   * set to half a second. So this is four of those windows.
   * <p>
   * It is a guard, not a measurement of speed. A machine which gives this JVM no turn only
   * makes the wait longer. Afterwards the test asserts a count which did not grow.
   */
  private static final long UNTIL_NOTHING_MORE_CAN_COME = 2000;

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
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:outbox-redispatch-mitigation-it;DB_CLOSE_DELAY=-1");

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  PerAdapterAwarenessSource awareness;

  @Inject
  UserTransaction userTransaction;

  @Inject
  JdbcTaskDeliveryLog deliveryLog;

  @Inject
  DataSource dataSource;

  /** Something the test does in a transaction of its own. */
  @FunctionalInterface
  interface InATransaction<T> {

    T run() throws Exception;

  }

  private <T> T inATransaction(
      final InATransaction<T> work) throws Exception {

    userTransaction.begin();
    try {
      final var result = work.run();
      userTransaction.commit();
      return result;
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

  }

  @Test
  @DisplayName("A retried START entry whose workflow is already known is consumed without a second start")
  public void retriedStartEntryDoesNotStartASecondWorkflow() throws Exception {

    listener.reset();
    awareness.reset();

    // the adapter reports the workflow as already known. That is like a crash or failure
    // right after a successful start, whose state is visible on the retry
    awareness.answerFor("test", WorkflowAwareness.ACTIVE);

    // the FIRST dispatch fails (BPMS "unreachable"). The entry stays pending, and its
    // ATTEMPTS column now carries the attempt which failed
    listener.failNextDispatches(1);

    userTransaction.begin();
    try {
      workflowService.startWorkflow("redispatch-mitigation");
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

    // the first (failing) dispatch happened. previouslyAttempted was false, so no
    // mitigation probe ran up to now
    listener.awaitInvocations(1, 30_000);
    assertEquals(0, awareness.countProbesOf("test"), "the first dispatch must not probe");

    // the RETRY sees attempts > 0 and probes the adapter, which answers ACTIVE. So it uses
    // up the entry without a second start
    final var deadline = System.currentTimeMillis() + 30_000;
    while ((awareness.countProbesOf("test") == 0) && (System.currentTimeMillis() < deadline)) {
      Thread.sleep(50);
    }
    assertTrue(
        awareness.countProbesOf("test") > 0,
        "the retried entry must probe the recorded adapter's workflow awareness");

    // give the dispatcher time for more (wrong) dispatches. Then assert that phase two
    // never reached the adapter again
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertEquals(
        1,
        listener.getInvocations().size(),
        "the mitigated retry must not start a second workflow; invocations: "
            + listener.getInvocations());

  }

  @Test
  @DisplayName("A retried second START of an aggregate does not take the first workflow for its own and starts")
  public void retriedSecondStartIsNotSkippedForTheFirstWorkflow() throws Exception {

    listener.reset();
    awareness.reset();
    awareness.answerFor("test", WorkflowAwareness.UNKNOWN_TO_BPMS);
    final var outbox = PhaseTwoOutboxReader.ofTheVanillaBpOutbox(dataSource);

    // the first workflow of the aggregate starts, and its entry is done. That frees the key
    // which the second start is planned under
    final var aggregate = inATransaction(() -> workflowService.startWorkflow("first-workflow"));
    listener.awaitInvocations(1, 30_000);
    final var aggregateId = aggregate.getId().toString();
    final var deadline = System.currentTimeMillis() + 30_000;
    while (!outbox
        .entries()
        .stream()
        .filter(entry -> aggregateId.equals(entry.aggregateId()))
        .allMatch(PhaseTwoOutboxReader.Entry::wasDispatched)) {
      assertTrue(System.currentTimeMillis() < deadline, "the first start was not marked done in time");
      Thread.sleep(50);
    }
    final var firstStart = outbox
        .entries()
        .stream()
        .filter(entry -> aggregateId.equals(entry.aggregateId()))
        .filter(entry -> PhaseOperation.START_WORKFLOW.name().equals(entry.operation()))
        .findFirst()
        .orElseThrow();

    // the row which the first start leaves behind. The dummy adapter reports no workflow id,
    // so the test writes the row here, as an adapter which reports one would leave it. It is
    // dated a day before the second start
    inATransaction(() -> deliveryLog
        .recordWorkflowStart(
            TaskDelivery
                .workflowStart(
                    "test",
                    firstStart.workflowModuleId(),
                    firstStart.bpmnProcessId(),
                    aggregateId,
                    "first-workflow-of-"
                        + aggregateId,
                    null,
                    Instant.now().minus(Duration.ofDays(1)))));

    // the second workflow: its first dispatch fails before it creates anything
    listener.failNextDispatches(1);
    inATransaction(() -> workflowService.startWorkflowAgain(aggregate));

    // the retry reads the row of the first workflow, which is older than the entry. So it
    // asks the adapter about workflows started since then. The adapter knows none, so the
    // retry starts
    listener.awaitInvocations(3, 30_000);
    assertEquals(
        3,
        listener.getInvocations().size(),
        "the first start, the failed attempt of the second one and its retry; got: "
            + listener.getInvocations());
    assertTrue(
        awareness.countProbesOf("test") > 0,
        "the retry has to ask the adapter, since the row belongs to the first workflow");

  }

}
