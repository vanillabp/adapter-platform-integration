package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.vanillabp.integration.adapter.migration.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.adapter.migration.processservice.PhaseTwoRouter;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;
import io.vanillabp.spi.process.ProcessService;

/**
 * Two operations on one workflow may run at the same moment, and an embedded engine then
 * fails one of them (Camunda 7: <code>OptimisticLockingException</code>). The loser goes
 * through a moment later. So the core repeats it at once, and the outbox does not wait its
 * backoff, which starts at <code>vanillabp.outbox.attempt-frequency</code>, thirty seconds by
 * default. The double reports its <code>DummyCollision</code> as such a race.
 * <p>
 * The proof is the attempt counter of the entry. The store counts every attempt it
 * makes, the one which went through as well. A repetition inside the dispatch is not
 * counted. The time is no proof here: these tests
 * set the attempt frequency to half a second, which is not much longer than the pauses
 * between the repetitions.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = TestApplication.class)
public class ACollisionIsRepeatedAtOnceTest {

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private DataSource dataSource;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @BeforeEach
  public void resetListener() {

    listener.reset();

  }

  private Aggregate startWorkflow(
      final String content) {

    return transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent(content);
      return processService.startWorkflow(aggregate);
    });

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
    final var warnings = new ListAppender<ILoggingEvent>();
    warnings.start();
    final var dispatcherLog = (Logger) LoggerFactory.getLogger(JdbcPhaseTwoOutboxDispatcher.class);
    dispatcherLog.addAppender(warnings);

    final Entry entry;
    final Aggregate aggregate;
    try {
      aggregate = startWorkflow("a-lost-race");
      entry = dispatchedEntryOf(aggregate.getId());
    } finally {
      dispatcherLog.detachAppender(warnings);
      warnings.stop();
    }

    assertEquals(2, invocationsOf(aggregate.getId()), "the lost race and its repetition");
    assertEquals(
        1,
        entry.attempts(),
        "the store counted the lost race as a failed attempt, so it waited for its backoff");
    // a race is expected, so it is not worth the WARN a failed attempt writes
    assertTrue(
        warnings.list
            .stream()
            .noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.WARN)),
        "the store heard of the lost race");

  }

  @Test
  @DisplayName("A race which is lost again and again goes to the store as one failed attempt")
  public void aRaceLostEveryTimeGoesToTheStore() throws Exception {

    // the first dispatch and each of its repetitions lose. The next attempt of the store wins
    final var lostRaces = 1 + PhaseTwoRouter.PAUSES_BEFORE_REPEATING_A_COLLISION.size();
    listener.failNextDispatchesWithACollision(lostRaces);

    final var aggregate = startWorkflow("a-race-lost-every-time");
    final var entry = dispatchedEntryOf(aggregate.getId());

    assertEquals(lostRaces + 1, invocationsOf(aggregate.getId()));
    // one failed attempt, and the one which went through
    assertEquals(2, entry.attempts(), "the repetitions are bounded, and their end is one failed attempt");

  }

}
