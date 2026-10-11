package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

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
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;
import io.vanillabp.spi.process.ProcessService;

/**
 * What happens to a phase-two call which an adapter cannot serve yet. On Camunda 8, the
 * exporter of the cluster has not written the workflow yet. The adapter says so and names
 * how long that takes. This is the ordinary case, not a failure.
 * <p>
 * Such an attempt ENDS. The store gets the entry back, gives it the due time the adapter
 * named, and dispatches it again. So the consumer sees the call once, and the entry is
 * ticked off. A store used to repeat the attempt on the spot, and that could not work. A
 * dispatch runs in the transaction of its aggregate. The rejected attempt marked that
 * transaction rollback-only. The attempt after it reached the BPMS and then lost its
 * commit. So the call went out while the entry stayed open, and what the handler had
 * written was rolled back with the transaction. Later the entry came again, and the
 * consumer got the same call twice.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = TestApplication.class)
public class ARejectedDispatchIsPlannedAgainTest {

  /**
   * The BPMN process the workflow service serves. The service names none, so the
   * convention applies: the class name is the process id.
   */
  private static final String PROCESS = SampleWorkflowService.class.getSimpleName();

  /**
   * How long an assertion waits for something the outbox does on its own thread. It is long
   * enough for a loaded build machine. It is a deadline, not a pause: a passing test stops
   * waiting as soon as the entry moved.
   */
  private static final long PATIENCE = 20000L;

  /**
   * The window a rejected dispatch names in the test below. Nothing there waits for it. The
   * entry comes back when the test makes it due, so the window may be this long. It is
   * many times longer than every deadline of that test. So the rejected entry cannot come
   * back by itself while the test still runs.
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
   * The key the operation of the extension derives. Only this key finds the row of one
   * aggregate among everything in the shared table of this module.
   */
  private static String idempotencyKeyOf(
      final Aggregate aggregate,
      final String event) {

    return "%s|%s|%s|%s".formatted("test-module", PROCESS, aggregate.getId(), event);

  }

  /**
   * The entry of that operation, or nothing where no entry carries that key.
   */
  private Optional<Entry> entryOf(
      final String idempotencyKey) {

    return PhaseTwoOutboxReader
        .ofTheVanillaBpOutbox(dataSource)
        .entries()
        .stream()
        .filter(entry -> idempotencyKey.equals(entry.idempotencyKey()))
        .findFirst();

  }

  /**
   * Whether the entry of that operation was ticked off. A dispatched entry is marked DONE
   * and kept until its retention runs out.
   */
  private boolean isTickedOff(
      final String idempotencyKey) {

    return entryOf(idempotencyKey)
        .filter(Entry::wasDispatched)
        .isPresent();

  }

  /**
   * Waits until the entry of that operation is ticked off for good. The status is read
   * twice, because the handler of the dispatch runs before the entry is marked. A read may
   * find the mark of an attempt which is still running.
   */
  private void awaitTickedOff(
      final String idempotencyKey) throws InterruptedException {

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
   * How often the store counted an attempt on the entry of that operation. Zero if no
   * entry carries that key.
   */
  private int attemptsOf(
      final String idempotencyKey) {

    return entryOf(idempotencyKey)
        .map(Entry::attempts)
        .orElse(0);

  }

  /**
   * Waits until the extension was asked once. That first call gets the rejection the
   * extension was told to give. The store counts no attempt for this kind of rejection, so
   * the number on the entry cannot show it. The count of the extension itself shows which
   * entry used up the rejection.
   */
  private void awaitRejected() throws InterruptedException {

    final var deadline = System.currentTimeMillis() + PATIENCE;
    while (extension.getAttempts() == 0) {
      if (System.currentTimeMillis() > deadline) {
        throw new AssertionError("The extension was never asked");
      }
      Thread.sleep(50);
    }

  }

  /**
   * Makes the entry of that operation due now, whatever due time it carries.
   */
  private void makeDueNow(
      final String idempotencyKey) {

    PhaseTwoOutboxReader
        .ofTheVanillaBpOutbox(dataSource)
        .makeDueNow(idempotencyKey);

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
    // no poll takes an entry marked DONE again. So "once" is an answer of the store, not
    // of a pause in this test
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
        1,
        attemptsOf(key),
        "the store counts the attempt which went through and not the rejection: waiting for a "
            + "read model is no failure, and counting it blocked entries after minutes");
    assertEquals(
        SampleWorkflowService.REPORTED_BY_THE_HANDLER,
        aggregates
            .findById(aggregate.getId())
            .orElseThrow()
            .getReported(),
        "what the handler wrote while the call went out has to stand with that call");

  }

  /**
   * The other half of ending a rejected attempt. One thread claims the due entries of this
   * store. So an entry which waited for its BPMS held up the entries of every other
   * workflow. Now nothing waits any more. So here the consumer sees the call of the
   * workflow nobody is waiting for.
   * <p>
   * The rejected entry gets a window which outlasts the test, so it cannot come back while
   * the test runs. The order below relies on that, not on the speed of the machine. A
   * store which ends the rejected attempt dispatches the next entry at once. A store which
   * waits for the rejected one dispatches nothing, however long anybody waits. At the end
   * the test makes the rejected entry due itself, so nothing of it is left for the classes
   * which follow.
   */
  @Test
  @DisplayName("A workflow which is not searchable yet does not hold the entries behind it")
  public void anEntryWhichIsNotDueYetLetsTheOthersPass() throws Exception {

    extension.rejectNextDispatches(1, LONGER_THAN_THIS_TEST_CAN_TAKE);

    final var waiting = startWorkflowAndSchedule("not-searchable-yet", "created");
    // the rejection belongs to this workflow, as the call to the extension shows. If the
    // second workflow were scheduled before that, it could get the rejection
    awaitRejected();
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
