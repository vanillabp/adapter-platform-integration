package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.sql.DataSource;

import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.spi.WorkflowAwareness;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.MessageAfterUserTaskEngine;
import io.vanillabp.integration.test.SteerableTaskAwarenessSource;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * An application completes a user task and correlates the message which the workflow waits
 * for after it, one call after the other. On an embedded engine phase one of the
 * correlation used to refuse: the completion was still in the outbox, so the engine did
 * not wait for the message yet. Now the refusal stands only where nothing of the workflow
 * waits in the outbox, and the correlation reaches the engine after the completion.
 * <p>
 * {@link MessageAfterUserTaskEngine} plays the engine. It holds the completion inside its
 * dispatch, so the correlation is planned while the completion surely has not reached the
 * engine. The Quarkus JDBC store answers whether something of the workflow still waits.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ACorrelationRightAfterACompletionTest {

  /** The sentence of the double's refusal which both cases name. */
  private static final String REFUSAL = "waits for message 'ContractSigned'";

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .setArchiveProducer(() -> ShrinkWrap
          .create(JavaArchive.class)
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(MessageAfterUserTaskEngine.class)
          .addClass(SteerableTaskAwarenessSource.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url",
          "jdbc:h2:mem:correlation-right-after-a-completion-it;DB_CLOSE_DELAY=-1");

  @Inject
  WorkflowService workflowService;

  @Inject
  MessageAfterUserTaskEngine engine;

  @Inject
  SteerableTaskAwarenessSource awareness;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  @BeforeEach
  public void reset() {

    engine.reset();
    awareness.answerWith(WorkflowAwareness.ACTIVE);
    awareness.alwaysVisible();

  }

  @AfterEach
  public void forgetWhatThisTestSteered() {

    engine.reset();

  }

  @Test
  @DisplayName("A message correlated right after completing the user task reaches the engine after the completion")
  public void theCorrelationFollowsTheCompletion() throws Exception {

    final var aggregate = startedAggregate("right-after-the-completion");
    engine.play(aggregate.getId());
    engine.holdTheCompletion();

    inTransaction(() -> workflowService.completeUserTask(aggregate, "utask-1"));
    try {
      // the completion is in the outbox, or held inside its dispatch. Either way the engine
      // does not wait for the message yet
      inTransaction(() -> workflowService.correlateMessage(aggregate, "ContractSigned"));
    } finally {
      engine.releaseTheCompletion();
    }

    awaitNothingLeftUndone(aggregate);
    assertEquals(
        List
            .of(
                "completed:%s:utask-1".formatted(aggregate.getId()),
                "correlated:%s:ContractSigned".formatted(aggregate.getId())),
        engine.events(),
        "the correlation reached the engine after the completion");

  }

  @Test
  @DisplayName("A message nothing waits for is still refused at once where nothing of the workflow is in the outbox")
  public void aRefusalStandsWhereNothingWaits() throws Exception {

    final var aggregate = startedAggregate("nothing-in-the-outbox");
    engine.play(aggregate.getId());

    final var refusal = assertThrows(
        IllegalStateException.class,
        () -> inTransaction(() -> workflowService.correlateMessage(aggregate, "ContractSigned")));

    assertTrue(refusal.getMessage().contains(REFUSAL), refusal.getMessage());
    assertEquals(0, countEntriesNotDone(aggregate), "a refused correlation leaves no entry behind");

  }

  private void inTransaction(
      final Runnable work) throws Exception {

    userTransaction.begin();
    try {
      work.run();
      userTransaction.commit();
    } catch (final Exception e) {
      userTransaction.rollback();
      throw e;
    }

  }

  /**
   * A workflow whose start is through: its entry is DONE. Otherwise the start would be the
   * entry which waits.
   */
  private Aggregate startedAggregate(
      final String content) throws Exception {

    final Aggregate[] started = new Aggregate[1];
    inTransaction(() -> started[0] = workflowService.startWorkflow(content));
    awaitNothingLeftUndone(started[0]);
    return started[0];

  }

  /**
   * Waits for the store, not for the engine. The engine hears of a dispatch one write
   * before the store marks the entry DONE.
   */
  private void awaitNothingLeftUndone(
      final Aggregate aggregate) throws Exception {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (countEntriesNotDone(aggregate) > 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "an entry of aggregate '%s' was never marked DONE".formatted(aggregate.getId()));
      Thread.sleep(50);
    }

  }

  private long countEntriesNotDone(
      final Aggregate aggregate) {

    return PhaseTwoOutboxReader
        .ofTheVanillaBpOutbox(dataSource)
        .entries()
        .stream()
        .filter(Entry::isWaiting)
        .filter(entry -> aggregate.getId().toString().equals(entry.aggregateId()))
        .count();

  }

}
