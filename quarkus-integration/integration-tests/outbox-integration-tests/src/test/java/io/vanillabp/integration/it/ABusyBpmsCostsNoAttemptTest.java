package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;

import javax.sql.DataSource;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
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
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * A BPMS under load refuses commands for a while (Camunda 8: backpressure, HTTP 503 with
 * <code>RESOURCE_EXHAUSTED</code>). The entry comes back after
 * <code>vanillabp.outbox.attempt-frequency</code>, and such a refusal uses no attempt of it. So
 * an entry which is fine is never blocked only because its BPMS was full. The double reports
 * its <code>DummyBusy</code> as such a refusal. The Spring Boot side has the same test, against
 * the same store.
 * <p>
 * The proof is the attempt counter of the entry. The store counts every attempt it makes, the
 * one which went through as well. And each refusal writes a WARN line, because that line is
 * where an overloaded BPMS becomes visible in operation. Quarkus hands the log records to the
 * extension of the test, which checks them once the application stopped.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ABusyBpmsCostsNoAttemptTest {

  /**
   * How often the BPMS refuses the dispatch. More than one, so a store which counted the
   * refusals would show it in the counter.
   */
  private static final int REFUSALS = 3;

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
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:outbox-busy-it;DB_CLOSE_DELAY=-1")
      // the message templates are compared, so the words of the line are found whatever the
      // parameters are
      .setLogRecordPredicate(record -> (record.getMessage() != null) && record
          .getMessage()
          .contains("met a busy BPMS"))
      .assertLogRecords(records -> {
        Assertions.assertEquals(REFUSALS, records.size(), "one line per refusal");
        Assertions.assertTrue(
            records
                .stream()
                // the log manager of Quarkus has a level of its own for WARN, with the value of
                // the JDK's WARNING
                .allMatch(record -> record.getLevel().intValue() == Level.WARNING.intValue()),
            "each refusal is a WARN");
      });

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
  @DisplayName("A busy BPMS uses no attempt of the entry")
  public void aBusyBpmsUsesNoAttempt() throws Exception {

    listener.failNextDispatchesWithABusyBpms(REFUSALS);

    final var aggregateId = startWorkflow("a-busy-bpms");
    final var entry = dispatchedEntryOf(aggregateId);

    assertEquals(REFUSALS + 1, invocationsOf(aggregateId), "the refusals and the attempt which went through");
    assertEquals(
        1,
        entry.attempts(),
        "the store counted a refusal of the busy BPMS as a failed attempt");

  }

}
