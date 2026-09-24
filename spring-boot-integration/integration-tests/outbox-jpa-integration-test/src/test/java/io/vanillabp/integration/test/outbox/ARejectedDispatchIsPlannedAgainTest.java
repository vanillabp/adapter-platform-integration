package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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
 * off. Repeating the attempt on the spot was what a store used to do, and it could not
 * work: a dispatch runs in the transaction of its aggregate, the rejected attempt marked
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

  /**
   * The window a rejected dispatch names in the test below. Nothing there waits for it:
   * the entry comes back when the test makes it due, which is why the window may be this
   * long. It outlasts every deadline of that test many times over, so the rejected entry
   * cannot come back on its own while the test is still running.
   */
  private static final Duration LONGER_THAN_THIS_TEST_CAN_TAKE = Duration.ofMinutes(5);

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
   * The key the extension's operation derives, which is the one thing which finds the row
   * of one aggregate among everything the shared table of this module holds.
   */
  private static String idempotencyKeyOf(
      final Aggregate aggregate,
      final String event) {

    return "%s|%s|%s|%s".formatted("test-module", PROCESS, aggregate.getId(), event);

  }

  /**
   * Whether the entry of that operation was ticked off: a dispatched entry is marked DONE
   * and kept until its retention runs out.
   */
  private boolean isTickedOff(
      final String idempotencyKey) throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection
        .prepareStatement("SELECT STATUS FROM VANILLABP_PHASE_TWO_OUTBOX WHERE IDEMPOTENCY_KEY = ?")) {
      statement.setString(1, idempotencyKey);
      try (var resultSet = statement.executeQuery()) {
        return resultSet.next() && "DONE".equals(resultSet.getString(1));
      }
    }

  }

  /**
   * Waits until the entry of that operation is ticked off for good. The status is asked
   * twice because the handler of the dispatch runs before the entry is marked: a read may
   * find the mark of an attempt which is still running.
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

  /**
   * How often the store counted an attempt on the entry of that operation, and zero
   * where no entry carries that key.
   */
  private int attemptsOf(
      final String idempotencyKey) throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection
        .prepareStatement("SELECT ATTEMPTS FROM VANILLABP_PHASE_TWO_OUTBOX WHERE IDEMPOTENCY_KEY = ?")) {
      statement.setString(1, idempotencyKey);
      try (var resultSet = statement.executeQuery()) {
        return resultSet.next() ? resultSet.getInt(1) : 0;
      }
    }

  }

  /**
   * Waits until the store counted an attempt on that entry. The store writes the count
   * when the attempt ENDED, so this is what says that a rejection was used up and which
   * entry used it.
   */
  private void awaitAttempted(
      final String idempotencyKey) throws Exception {

    final var deadline = System.currentTimeMillis() + PATIENCE;
    while (attemptsOf(idempotencyKey) == 0) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError(
            "The outbox entry of '%s' was never attempted".formatted(idempotencyKey));
      }
      Thread.sleep(50);
    }

  }

  /**
   * Makes the entry of that operation due now, whatever due time it carries.
   */
  private void makeDueNow(
      final String idempotencyKey) throws SQLException {

    try (var connection = dataSource.getConnection(); var statement = connection
        .prepareStatement("UPDATE VANILLABP_PHASE_TWO_OUTBOX SET NEXT_ATTEMPT_AT = ? WHERE IDEMPOTENCY_KEY = ?")) {
      statement.setTimestamp(1, Timestamp.from(Instant.now()));
      statement.setString(2, idempotencyKey);
      statement.executeUpdate();
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
    // an entry marked DONE is one no poll takes again, so this is what makes "once" an
    // answer of the store rather than of a pause in this test
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
   * The other half of ending a rejected attempt: one thread claims the due entries of this
   * store, so an entry which waited for its BPMS held every other workflow's entry with it.
   * Nothing waits any more, which is why the call of the workflow nobody is waiting for is
   * the one the consumer sees here.
   * <p>
   * The rejected entry is given a window which outlasts the test, so it cannot come back
   * while the test runs. The order below rests on that and not on the speed of the
   * machine. A store which ends the rejected attempt dispatches the entry behind it at
   * once, and a store which waits for the rejected one dispatches nothing, however long
   * anybody waits for it. The test makes the rejected entry due itself at the end, so
   * nothing of it is left standing for the classes which follow.
   */
  @Test
  @DisplayName("A workflow which is not searchable yet does not hold the entries behind it")
  public void anEntryWhichIsNotDueYetLetsTheOthersPass() throws Exception {

    extension.rejectNextDispatches(1, LONGER_THAN_THIS_TEST_CAN_TAKE);

    final var waiting = startWorkflowAndSchedule("not-searchable-yet", "created");
    // the rejection belongs to this workflow, and the store counting the attempt is what
    // says so. Scheduling the second workflow before that could hand the rejection to it
    awaitAttempted(idempotencyKeyOf(waiting, "created"));
    final var passing = startWorkflowAndSchedule("searchable", "created");

    final var dispatched = extension.awaitDispatched(1, PATIENCE);

    assertEquals(
        List
            .of(
                passing
                    .getId()
                    .toString()),
        dispatched
            .stream()
            .map(PhaseTwoCall::workflowAggregateId)
            .toList(),
        "the rejected entry was waited for, so everything behind it waited too");
    awaitTickedOff(idempotencyKeyOf(passing, "created"));

    makeDueNow(idempotencyKeyOf(waiting, "created"));
    awaitTickedOff(idempotencyKeyOf(waiting, "created"));

  }

}
