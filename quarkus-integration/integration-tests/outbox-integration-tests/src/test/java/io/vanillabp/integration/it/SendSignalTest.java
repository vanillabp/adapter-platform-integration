package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Broadcasting a BPMN signal on Quarkus (story 42) with the dummy adapter forced to
 * require a two-phase commit: the broadcast of a REMOTE BPMS may only happen after
 * the local transaction was committed, which is what the outbox entry is for. A
 * rolled-back transaction broadcasts nothing at all.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SendSignalTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("send-signal.yaml", "application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:send-signal-it;DB_CLOSE_DELAY=-1");

  private static final String COUNT_SIGNAL_ENTRIES = "SELECT COUNT(*) FROM VANILLABP_PHASE_TWO_OUTBOX "
      + "WHERE OPERATION = 'SEND_SIGNAL'";

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  private long count(
      final String query) throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .createStatement(); var resultSet = statement.executeQuery(query)) {
      resultSet.next();
      return resultSet.getLong(1);
    }

  }

  private List<String> awaitBroadcast(
      final int count) throws InterruptedException {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (listener.getBroadcastSignals().size() < count) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(
            "only %d of %d broadcasts happened".formatted(listener.getBroadcastSignals().size(), count));
      }
      Thread.sleep(50);
    }
    return listener.getBroadcastSignals();

  }

  @Test
  @DisplayName("A remote BPMS broadcasts after the commit, through an outbox entry carrying no aggregate")
  public void broadcastHappensAfterTheCommit() throws Exception {

    userTransaction.begin();
    workflowService.sendSignal("OrderReceived");
    // the entry rides the transaction; nothing was broadcast yet
    assertEquals(1, count(COUNT_SIGNAL_ENTRIES));
    assertTrue(listener.getBroadcastSignals().isEmpty());
    userTransaction.commit();

    final var broadcast = awaitBroadcast(1);
    assertEquals(List.of("OrderReceived/phase-two"), broadcast);

    // the entry has no aggregate: a broadcast is not about one workflow
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM VANILLABP_PHASE_TWO_OUTBOX WHERE OPERATION = 'SEND_SIGNAL' "
                + "AND AGGREGATE_ID IS NULL"));

  }

  @Test
  @DisplayName("On rollback the entry is gone and nothing is broadcast")
  public void rollbackBroadcastsNothing() throws Exception {

    final var entriesBefore = count(COUNT_SIGNAL_ENTRIES);
    final var broadcastsBefore = listener.getBroadcastSignals().size();

    userTransaction.begin();
    workflowService.sendSignal("RolledBack");
    assertEquals(entriesBefore + 1, count(COUNT_SIGNAL_ENTRIES));
    userTransaction.rollback();

    assertEquals(entriesBefore, count(COUNT_SIGNAL_ENTRIES));

    // wait longer than the poll interval: nothing may ever be broadcast
    Thread.sleep(1500);
    assertEquals(broadcastsBefore, listener.getBroadcastSignals().size());

  }

}
