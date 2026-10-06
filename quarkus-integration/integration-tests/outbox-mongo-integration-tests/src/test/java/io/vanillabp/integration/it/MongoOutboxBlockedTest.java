package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.mongodb.client.MongoClient;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.MongoPhaseTwoOutboxReader;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * Poison-entry path of the MongoDB-based phase-two outbox: after
 * <code>vanillabp.outbox.block-after-attempts</code> (here: 2) failed dispatches
 * the entry is marked BLOCKED, left as a monitorable trail and never retried again.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MongoOutboxBlockedTest {

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
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("quarkus.mongodb.database", "outbox-blocked-it")
      .overrideConfigKey("vanillabp.outbox.block-after-attempts", "2");

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  MongoClient mongoClient;

  /**
   * The outbox of the application, asked through the reader so that neither a collection
   * nor a field is named here.
   */
  private MongoPhaseTwoOutboxReader outbox() {

    return MongoPhaseTwoOutboxReader.ofTheVanillaBpOutbox(mongoClient.getDatabase("outbox-blocked-it"));

  }

  @Test
  @DisplayName("A permanently failing entry is BLOCKED after the configured attempts")
  public void permanentlyFailingEntryIsBlocked() throws Exception {

    listener.failNextDispatches(Integer.MAX_VALUE);
    final var errors = new java.util.concurrent.CopyOnWriteArrayList<String>();
    final var errorWatcher = errorsInto(errors);
    final var dispatcherLog = java.util.logging.Logger
        .getLogger(io.vanillabp.integration.runtime.outbox.MongoPhaseTwoOutboxDispatcher.class.getName());
    dispatcherLog.addHandler(errorWatcher);

    try {
      userTransaction.begin();
      workflowService.startWorkflow("blocked-test");
      userTransaction.commit();

      // wait until the entry is marked BLOCKED
      final var deadline = System.currentTimeMillis() + 10000;
      while (outbox().entriesBlocked() == 0) {
        assertTrue(System.currentTimeMillis() < deadline, "entry was not blocked");
        Thread.sleep(50);
      }

      // exactly block-after-attempts dispatches happened, then no more
      assertEquals(2, listener.getInvocations().size());
      Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
      assertEquals(2, listener.getInvocations().size(), "a BLOCKED entry must not be retried");
    } finally {
      dispatcherLog.removeHandler(errorWatcher);
    }

    // the ERROR is where an operator starts, so it names the entry and the page which says
    // how to find it, open it again or delete it
    final var blockedEntry = outbox()
        .entries()
        .stream()
        .filter(MongoPhaseTwoOutboxReader.Entry::isBlocked)
        .findFirst()
        .orElseThrow()
        .id();
    assertTrue(
        errors
            .stream()
            .anyMatch(error -> error.contains(blockedEntry) && error.contains(
                io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties.BLOCKED_ENTRIES_GUIDE)),
        "the ERROR of a blocked entry does not say where the way back is described: "
            + errors);

  }

  /**
   * Collects what is logged at ERROR, the message together with its parameters, because
   * the log manager of Quarkus hands the parameters over unformatted.
   *
   * @param errors Where the lines go
   * @return The handler to add to a logger
   */
  private static java.util.logging.Handler errorsInto(
      final java.util.List<String> errors) {

    return new java.util.logging.Handler() {

      @Override
      public void publish(
          final java.util.logging.LogRecord record) {

        if (record.getLevel().intValue() >= java.util.logging.Level.SEVERE.intValue()) {
          errors.add(record.getMessage()
              + " "
              + java.util.Arrays.toString(record.getParameters()));
        }

      }

      @Override
      public void flush() {
      }

      @Override
      public void close() {
      }

    };

  }

}
