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
import io.vanillabp.integration.spi.PhaseOperation;
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
 * Broadcasting a BPMN signal on Quarkus with the dummy adapter forced to
 * require a two-phase commit: the broadcast of a REMOTE BPMS may only happen after
 * the local transaction was committed, which is what the outbox entry is for. A
 * rolled-back transaction broadcasts nothing at all.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SendSignalTest {

  /**
   * How long a test waits before it says that nothing more happened. The application
   * dispatches every <code>vanillabp.outbox.attempt-frequency</code>, which these tests
   * configure as half a second, so this is three of those windows.
   * <p>
   * It is a guard and not a measurement of speed: a machine which leaves this JVM without
   * a turn only makes the wait longer, and what is asserted afterwards is a count which
   * did not grow.
   */
  private static final long UNTIL_NOTHING_MORE_CAN_COME = 1500;

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

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  /**
   * The entries of the broadcast. The data source of a Quarkus application hands out a
   * connection of the running JTA transaction, so an entry is read while the transaction
   * which wrote it is still open.
   *
   * @return The entries
   */
  private List<Entry> signalEntries() {

    return PhaseTwoOutboxReader
        .ofTheVanillaBpOutbox(dataSource)
        .entries()
        .stream()
        .filter(entry -> PhaseOperation.SEND_SIGNAL.name().equals(entry.operation()))
        .toList();

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
    assertEquals(1, signalEntries().size());
    assertTrue(listener.getBroadcastSignals().isEmpty());
    userTransaction.commit();

    final var broadcast = awaitBroadcast(1);
    assertEquals(List.of("OrderReceived/phase-two"), broadcast);

    // the entry has no aggregate: a broadcast is not about one workflow
    assertEquals(
        1,
        signalEntries()
            .stream()
            .filter(entry -> entry.aggregateId() == null)
            .count());

  }

  @Test
  @DisplayName("On rollback the entry is gone and nothing is broadcast")
  public void rollbackBroadcastsNothing() throws Exception {

    final var entriesBefore = signalEntries().size();
    final var broadcastsBefore = listener.getBroadcastSignals().size();

    userTransaction.begin();
    workflowService.sendSignal("RolledBack");
    assertEquals(entriesBefore + 1, signalEntries().size());
    userTransaction.rollback();

    assertEquals(entriesBefore, signalEntries().size());

    // wait longer than the poll interval: nothing may ever be broadcast
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertEquals(broadcastsBefore, listener.getBroadcastSignals().size());

  }

}
