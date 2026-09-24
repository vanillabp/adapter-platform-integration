package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.function.Function;

import javax.sql.DataSource;

import org.springframework.context.ConfigurableApplicationContext;

import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;

/**
 * What a crashed application leaves behind, read from the table the outbox keeps.
 * <p>
 * Every test which restarts an application needs the same state first: an entry which
 * was dispatched, failed and carries that attempt. The listener of the dummy adapter
 * runs INSIDE the dispatch, so a test closing the context right after it can take the
 * database away before the store counted the attempt, and the second context then
 * recovers an entry of a shape the test never meant.
 * <p>
 * The table and its columns belong to {@link PhaseTwoOutboxReader}, which takes every
 * name from the platform class declaring it. So this class asks for entries and reads
 * their attempts, and a rename of a table or of a state is followed in the reader alone.
 */
public final class FailedAttempts {

  /**
   * How long the wait goes on before an outbox counts as stopped. It guards and it
   * measures nothing: what the test claims is read from the table below, so a machine
   * carrying several builds makes this wait longer rather than red.
   */
  private static final long UNTIL_AN_OUTBOX_COUNTS_AS_STOPPED = 30000;

  /**
   * Which store the application runs, because the two write tables of their own.
   * Gruelbox is here for the one test which still asks for it
   * (<code>vanillabp.outbox.gruelbox.enabled</code>). Each constant names the reader
   * which knows that table.
   */
  public enum Store {

    VANILLABP(PhaseTwoOutboxReader::ofTheVanillaBpOutbox),

    GRUELBOX(PhaseTwoOutboxReader::ofTheGruelboxOutbox);

    private final Function<DataSource, PhaseTwoOutboxReader> readerOfThisStore;

    Store(
        final Function<DataSource, PhaseTwoOutboxReader> readerOfThisStore) {

      this.readerOfThisStore = readerOfThisStore;

    }

    /**
     * @param dataSource The database of the application under test
     * @return The reader of this store's table
     */
    PhaseTwoOutboxReader readerOf(
        final DataSource dataSource) {

      return readerOfThisStore.apply(dataSource);

    }

  }

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

    awaitWrittenDown(context, attempted, Store.VANILLABP);

  }

  /**
   * Waits until the given number of entries carry a failed attempt.
   *
   * @param context The running application
   * @param attempted How many entries have to carry one
   * @param store Which store the application runs
   */
  public static void awaitWrittenDown(
      final ConfigurableApplicationContext context,
      final long attempted,
      final Store store) throws Exception {

    final var outbox = outboxOf(context, store);
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
   * Waits until a waiting entry was attempted the given number of times, which is how a
   * test says "the dispatcher tried again and again" without claiming how long that took.
   *
   * @param context The running application
   * @param attempts How many attempts one entry has to carry
   */
  public static void awaitAttemptsOfAWaitingEntry(
      final ConfigurableApplicationContext context,
      final long attempts) throws Exception {

    awaitAttemptsOfAWaitingEntry(context, attempts, Store.VANILLABP);

  }

  /**
   * Waits until a waiting entry was attempted the given number of times, which is how a
   * test says "the dispatcher tried again and again" without claiming how long that took.
   *
   * @param context The running application
   * @param attempts How many attempts one entry has to carry
   * @param store Which store the application runs
   */
  public static void awaitAttemptsOfAWaitingEntry(
      final ConfigurableApplicationContext context,
      final long attempts,
      final Store store) throws Exception {

    final var outbox = outboxOf(context, store);
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
   * The reader of the table this application writes. It is built once per wait: reading
   * a name costs a class lookup, while asking for the entries does not.
   *
   * @param context The running application
   * @param store Which store the application runs
   * @return The reader
   */
  private static PhaseTwoOutboxReader outboxOf(
      final ConfigurableApplicationContext context,
      final Store store) {

    return store.readerOf(context.getBean(DataSource.class));

  }

  /**
   * A blocked entry counts for neither of the two numbers below. It waits for a person
   * rather than for the next attempt, so a test waiting for attempts to come would wait
   * for something which never happens again.
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
