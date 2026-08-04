package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusUnitTest;
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
 * THE migration scenario on Quarkus (story 25): instances were started while
 * adapter 'old-bpms' was first priority; the configuration was then flipped
 * ('new-bpms' promoted). Operations on OLD instances still route to 'old-bpms'
 * (probing election), NEW workflows start in 'new-bpms', and the second operation
 * on the same workflow skips the walk via the election cache (the demoted-first
 * adapter is not probed again).
 */
@ExtendWith(SuppressOutputExtension.class)
public class MigrationElectionTest {

  @RegisterExtension
  static final QuarkusUnitTest unitTest = new QuarkusUnitTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addAsResource("migration-election.yaml", "application.yaml")
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

  @BeforeEach
  public void reset() {

    listener.reset();
    awareness.reset();

  }

  private void inTransaction(
      final Runnable operation) throws Exception {

    userTransaction.begin();
    try {
      operation.run();
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

  }

  @Test
  @DisplayName("Old instances route to the demoted adapter, new starts to the promoted one, the cache skips the walk")
  public void oldInstancesRouteToOldBpmsNewStartsToNewBpms() throws Exception {

    // the OLD instance lives in 'old-bpms' (started before the priority flip)
    awareness.answerFor("old-bpms", WorkflowAwareness.ACTIVE);

    final var aggregateHolder = new Aggregate[1];
    inTransaction(() -> aggregateHolder[0] = workflowService.startWorkflow("migration-old-instance"));
    final var aggregate = aggregateHolder[0];

    // the NEW start went to the CURRENT first-priority adapter 'new-bpms'
    final var deadline = System.currentTimeMillis() + 15000;
    while (listener.getStartedByAdapter().isEmpty() && (System.currentTimeMillis() < deadline)) {
      Thread.sleep(50);
    }
    assertEquals(
        "new-bpms:"
            + aggregate.getId(),
        listener.getStartedByAdapter().getFirst(),
        "new workflows must start in the promoted first-priority adapter");

    // first operation on the OLD instance: the walk probes 'new-bpms' first
    // (UNKNOWN), then 'old-bpms' answers ACTIVE and executes
    inTransaction(() -> workflowService.completeTask(aggregate, "task-old-1"));
    awaitCompletedTasksByAdapter(1);
    assertTrue(
        listener
            .getCompletedTasksByAdapter()
            .getFirst()
            .startsWith("old-bpms:"),
        "the old instance's task must complete on 'old-bpms' but got: "
            + listener.getCompletedTasksByAdapter());
    final var newBpmsProbesAfterFirstOperation = awareness.countProbesOf("new-bpms");
    assertTrue(newBpmsProbesAfterFirstOperation > 0, "the walk must have probed the first-priority adapter");

    // second operation: the cache routes directly to 'old-bpms' - the
    // first-priority adapter is NOT probed again (neither in phase one nor at
    // phase-two dispatch)
    inTransaction(() -> workflowService.completeTask(aggregate, "task-old-2"));
    awaitCompletedTasksByAdapter(2);
    assertTrue(
        listener
            .getCompletedTasksByAdapter()
            .get(1)
            .startsWith("old-bpms:"),
        "the second operation must still execute on 'old-bpms' but got: "
            + listener.getCompletedTasksByAdapter());
    assertEquals(
        newBpmsProbesAfterFirstOperation,
        awareness.countProbesOf("new-bpms"),
        "the cached election must not probe the first-priority adapter again");

  }

  private void awaitCompletedTasksByAdapter(
      final int count) throws Exception {

    final var deadline = System.currentTimeMillis() + 15000;
    while (listener.getCompletedTasksByAdapter().size() < count) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "task completion was not dispatched in time; got: "
              + listener.getCompletedTasksByAdapter());
      Thread.sleep(50);
    }

  }

}
