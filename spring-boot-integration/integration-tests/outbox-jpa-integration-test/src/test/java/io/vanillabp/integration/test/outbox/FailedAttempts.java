package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;

/**
 * What a crashed application leaves behind, read from the table the outbox keeps.
 * <p>
 * Every test which restarts an application first needs the same state: an entry which
 * was dispatched, failed, and carries that attempt. The listener of the dummy adapter
 * runs INSIDE the dispatch. A test which closes the context right after the listener may
 * take the database away before the store counted the attempt. Then the second context
 * recovers an entry the test never meant.
 * <p>
 * The table and its columns belong to {@link PhaseTwoOutboxReader}. It takes every name
 * from the platform class which declares it. So this class only asks for entries and
 * reads their attempts. If a table or a state is renamed, only the reader follows.
 */
public final class FailedAttempts {

  /**
   * How long the wait goes on before an outbox counts as stopped. It is a guard, it
   * measures nothing. The test reads what it claims from the table. So on a machine with
   * several builds this wait gets longer, but the test does not turn red.
   */
  private static final long UNTIL_AN_OUTBOX_COUNTS_AS_STOPPED = 30000;

  private FailedAttempts() {
  }

  /**
   * Waits until the given number of entries carry a failed attempt.
   *
   * @param context The running application
   * @param attempted How many entries have to carry one
   */
  public static void awaitWrittenDown(
      final ConfigurableApplicationContext context,
      final long attempted) throws Exception {

    final var outbox = outboxOf(context);
    final var deadline = System.currentTimeMillis() + UNTIL_AN_OUTBOX_COUNTS_AS_STOPPED;
    var written = entriesCarryingAnAttempt(outbox);
    while (written < attempted) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "expected %d outbox entries carrying a failed attempt but found %d"
              .formatted(attempted, written));
      Thread.sleep(50);
      written = entriesCarryingAnAttempt(outbox);
    }

  }

  /**
   * Waits until a waiting entry was attempted the given number of times. So a test can say
   * "the dispatcher tried again and again" without claiming how long that took.
   *
   * @param context The running application
   * @param attempts How many attempts one entry has to carry
   */
  public static void awaitAttemptsOfAWaitingEntry(
      final ConfigurableApplicationContext context,
      final long attempts) throws Exception {

    final var outbox = outboxOf(context);
    final var deadline = System.currentTimeMillis() + UNTIL_AN_OUTBOX_COUNTS_AS_STOPPED;
    var attempted = mostAttemptsOfOneEntry(outbox);
    while (attempted < attempts) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "expected an outbox entry attempted %d times but the most attempted one carries %d"
              .formatted(attempts, attempted));
      Thread.sleep(50);
      attempted = mostAttemptsOfOneEntry(outbox);
    }

  }

  /**
   * The reader of the table this application writes. That is the table VanillaBP itself
   * writes.
   *
   * @param context The running application
   * @return The reader
   */
  private static PhaseTwoOutboxReader outboxOf(
      final ConfigurableApplicationContext context) {

    return PhaseTwoOutboxReader.ofTheVanillaBpOutbox(context.getBean(DataSource.class));

  }

  /**
   * A blocked entry counts for neither of the two numbers below. It waits for a person,
   * not for the next attempt. A test which waited for its attempts would wait for
   * something which never happens again.
   *
   * @param outbox The reader of the table this application writes
   * @return The highest number of attempts one waiting entry carries
   */
  private static long mostAttemptsOfOneEntry(
      final PhaseTwoOutboxReader outbox) {

    return outbox
        .entries()
        .stream()
        .filter(Entry::isWaiting)
        .mapToInt(Entry::attempts)
        .max()
        .orElse(0);

  }

  /**
   * @param outbox The reader of the table this application writes
   * @return How many waiting entries were attempted at least once
   */
  private static long entriesCarryingAnAttempt(
      final PhaseTwoOutboxReader outbox) {

    return outbox
        .entries()
        .stream()
        .filter(Entry::isWaiting)
        .filter(entry -> entry.attempts() > 0)
        .count();

  }

}
