package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.spi.PhaseTwoCall;
import io.vanillabp.integration.spi.PhaseTwoOutbox;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.ProcessService;

/**
 * What happens to a phase-two call an adapter cannot serve yet: on Camunda 8 the exporter
 * of the cluster has not written the workflow, the adapter says so and names how long that
 * takes, and this is the ordinary case rather than a failure.
 * <p>
 * Such an attempt ENDS. The store gets the entry back, gives it the due time the adapter
 * named and dispatches it again, so the consumer sees the call once and the entry is ticked
 * off. Repeating the attempt on the spot was what this store used to do, and it could not
 * work: gruelbox dispatches inside a transaction of its own, the rejected attempt marked
 * that transaction rollback-only, and the attempt behind it reached the BPMS and then lost
 * its commit. The call went out while the entry stayed open, and what the handler had written
 * went back with the transaction. The entry came again afterwards and the consumer got the
 * same call twice.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = TestApplication.class)
public class ARejectedDispatchIsPlannedAgainTest {

  /**
   * The BPMN process the workflow service serves. It names none, so the convention applies
   * and the class name is the process id.
   */
  private static final String PROCESS = SampleWorkflowService.class.getSimpleName();

  /**
   * How long an assertion waits for something the outbox does on its own thread. Long
   * enough for a loaded build machine, and it is a deadline rather than a pause: a test
   * which is right stops waiting as soon as the entry moved.
   */
  private static final long PATIENCE = 20000L;

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private PhaseTwoOutbox outbox;

  @Autowired
  private SampleExtension extension;

  @Autowired
  private AggregateRepository aggregates;

  @Autowired
  private DataSource dataSource;

  @BeforeEach
  public void resetExtension() {

    extension.reset();

  }

  private Aggregate startWorkflowAndSchedule(
      final String content,
      final String event) {

    return transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent(content);
      final var attached = processService.startWorkflow(aggregate);
      outbox
          .schedule(
              SampleExtension.call("test-module", PROCESS, attached.getId().toString(), event));
      return attached;
    });

  }

  /**
   * The key the extension's operation derives, which is gruelbox' unique request id and
   * therefore the one thing which finds the row of one aggregate among everything the shared
   * table of this module holds.
   */
  private static String idempotencyKeyOf(
      final Aggregate aggregate,
      final String event) {

    return "%s|%s|%s|%s".formatted("test-module", PROCESS, aggregate.getId(), event);

  }

  /**
   * Whether the entry of that operation was ticked off: gruelbox marks a dispatched entry
   * as processed and keeps it until its retention runs out.
   */
  private boolean isTickedOff(
      final String idempotencyKey) throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection
        .prepareStatement("SELECT processed FROM TXNO_OUTBOX WHERE uniqueRequestId = ?")) {
      statement.setString(1, idempotencyKey);
      try (var resultSet = statement.executeQuery()) {
        return resultSet.next() && resultSet.getBoolean(1);
      }
    }

  }

  /**
   * Waits until the entry of that operation is ticked off for good. The flag is asked twice
   * because gruelbox writes it inside the transaction of the dispatch: a read may find it
   * while that transaction is still open, and a dispatch which then loses its commit takes
   * the flag with it.
   */
  private void awaitTickedOff(
      final String idempotencyKey) throws Exception {

    final var deadline = System.currentTimeMillis() + PATIENCE;
    while (true) {
      if (isTickedOff(idempotencyKey)) {
        Thread.sleep(100);
        if (isTickedOff(idempotencyKey)) {
          return;
        }
      }
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(
            "The outbox entry of '%s' was never ticked off".formatted(idempotencyKey));
      }
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("A call rejected once reaches the consumer exactly once and the entry is ticked off")
  public void aRejectedCallIsDispatchedOnceAndTheEntryIsDone() throws Exception {

    extension.rejectNextDispatches(1, Duration.ofMillis(500));
    extension.writeWhileDispatching();

    final var aggregate = startWorkflowAndSchedule("rejected-once", "created");
    assertNotNull(aggregate);
    final var key = idempotencyKeyOf(aggregate, "created");

    extension.awaitDispatched(1, PATIENCE);
    // a ticked-off entry is one gruelbox never dispatches again, so this is what makes "once"
    // an answer of the store rather than of a pause in this test
    awaitTickedOff(key);

    assertEquals(
        1,
        extension.getDispatched().size(),
        "the call reached the consumer twice: the first dispatch lost its transaction");
    assertEquals(
        2,
        extension.getAttempts(),
        "one rejected attempt and one which went through is what this case costs");
    assertEquals(
        SampleWorkflowService.REPORTED_BY_THE_HANDLER,
        aggregates
            .findById(aggregate.getId())
            .orElseThrow()
            .getReported(),
        "what the handler wrote while the call went out has to stand with that call");

  }

  /**
   * The other half of ending a rejected attempt: one thread dispatches the entries of this
   * store, so an entry which waited for its BPMS held every other workflow's entry with it.
   * Nothing waits any more, which is why the call of the workflow nobody is waiting for is
   * the FIRST one the consumer sees here.
   */
  @Test
  @DisplayName("A workflow which is not searchable yet does not hold the entries behind it")
  public void anEntryWhichIsNotDueYetLetsTheOthersPass() throws Exception {

    extension.rejectNextDispatches(1, Duration.ofSeconds(2));

    final var waiting = startWorkflowAndSchedule("not-searchable-yet", "created");
    final var passing = startWorkflowAndSchedule("searchable", "created");

    final var dispatched = extension.awaitDispatched(2, PATIENCE);

    assertEquals(
        List
            .of(
                passing
                    .getId()
                    .toString(),
                waiting
                    .getId()
                    .toString()),
        dispatched
            .stream()
            .map(PhaseTwoCall::workflowAggregateId)
            .toList(),
        "the rejected entry was waited for, so everything behind it waited too");
    awaitTickedOff(idempotencyKeyOf(passing, "created"));
    awaitTickedOff(idempotencyKeyOf(waiting, "created"));

  }

}
