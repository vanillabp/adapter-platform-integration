package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Two operations on one workflow may run at the same moment, and an embedded engine then
 * fails one of them (Camunda 7: <code>OptimisticLockingException</code>). The loser goes
 * through a moment later. So the core repeats it at once, and the outbox does not wait its
 * backoff, which starts at <code>vanillabp.outbox.attempt-frequency</code>, thirty seconds by
 * default. The double reports its <code>DummyCollision</code> as such a race. The Spring Boot
 * side has the same test, against the same store.
 * <p>
 * The proof is the attempt counter of the entry. The store counts every attempt it makes,
 * the one which went through as well. A repetition inside the dispatch is not counted.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ACollisionIsRepeatedAtOnceTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(TestMeterRegistryProducer.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:outbox-collision-it;DB_CLOSE_DELAY=-1");

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  @BeforeEach
  public void resetListener() {

    listener.reset();

  }

  private Object startWorkflow(
      final String content) throws Exception {

    userTransaction.begin();
    final var aggregateId = workflowService
        .startWorkflow(content)
        .getId();
    userTransaction.commit();
    return aggregateId;

  }

  /**
   * Waits until the one entry of the aggregate was dispatched, and returns it.
   *
   * @param aggregateId The aggregate whose start was planned
   * @return Its entry, dispatched
   */
  private Entry dispatchedEntryOf(
      final Object aggregateId) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (true) {
      final var entries = PhaseTwoOutboxReader
          .ofTheVanillaBpOutbox(dataSource)
          .entries()
          .stream()
          .filter(entry -> aggregateId.toString().equals(entry.aggregateId()))
          .toList();
      if ((entries.size() == 1) && entries.get(0).wasDispatched()) {
        return entries.get(0);
      }
      assertTrue(System.currentTimeMillis() < deadline, "the entry was not dispatched: "
          + entries);
      Thread.sleep(50);
    }

  }

  private long invocationsOf(
      final Object aggregateId) {

    return listener
        .getInvocations()
        .stream()
        .filter(aggregateId::equals)
        .count();

  }

  @Test
  @DisplayName("A lost race is repeated at once and uses no attempt of the entry")
  public void aLostRaceIsRepeatedAtOnce() throws Exception {

    listener.failNextDispatchesWithACollision(1);

    final var aggregateId = startWorkflow("a-lost-race");
    final var entry = dispatchedEntryOf(aggregateId);

    assertEquals(2, invocationsOf(aggregateId), "the lost race and its repetition");
    assertEquals(
        1,
        entry.attempts(),
        "the store counted the lost race as a failed attempt, so it waited for its backoff");

  }

  @Test
  @DisplayName("A race which is lost again and again goes to the store as one failed attempt")
  public void aRaceLostEveryTimeGoesToTheStore() throws Exception {

    // the first dispatch and each of its repetitions lose. The next attempt of the store wins
    final var lostRaces = 1 + PhaseTwoRouter.PAUSES_BEFORE_REPEATING_A_COLLISION.size();
    listener.failNextDispatchesWithACollision(lostRaces);

    final var aggregateId = startWorkflow("a-race-lost-every-time");
    final var entry = dispatchedEntryOf(aggregateId);

    assertEquals(lostRaces + 1, invocationsOf(aggregateId));
    // one failed attempt, and the one which went through
    assertEquals(2, entry.attempts(), "the repetitions are bounded, and their end is one failed attempt");

  }

}
