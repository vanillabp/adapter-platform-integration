package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.springframework.context.ConfigurableApplicationContext;

/**
 * What a crashed application leaves behind, read from the table gruelbox keeps.
 * <p>
 * Every test which restarts an application needs the same state first: an entry which
 * was dispatched, failed and carries that attempt. The listener of the dummy adapter
 * runs INSIDE the dispatch, so a test closing the context right after it can take the
 * database away before gruelbox counted the attempt, and the second context then
 * recovers an entry of a shape the test never meant.
 */
public final class FailedAttempts {

  /**
   * How long the wait goes on before an outbox counts as stopped. It guards and it
   * measures nothing: what the test claims is read from the table below, so a machine
   * carrying several builds makes this wait longer rather than red.
   */
  private static final long UNTIL_AN_OUTBOX_COUNTS_AS_STOPPED = 30000;

  private static final String COUNT_ATTEMPTED = "SELECT COUNT(*) FROM TXNO_OUTBOX WHERE processed = false AND attempts > 0";

  private static final String MOST_ATTEMPTS = "SELECT COALESCE(MAX(attempts), 0) FROM TXNO_OUTBOX WHERE processed = false";

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

    final var dataSource = context.getBean(DataSource.class);
    final var deadline = System.currentTimeMillis() + UNTIL_AN_OUTBOX_COUNTS_AS_STOPPED;
    var written = entriesCarryingAnAttempt(dataSource);
    while (written < attempted) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "expected %d outbox entries carrying a failed attempt but found %d"
              .formatted(attempted, written));
      Thread.sleep(50);
      written = entriesCarryingAnAttempt(dataSource);
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

    final var dataSource = context.getBean(DataSource.class);
    final var deadline = System.currentTimeMillis() + UNTIL_AN_OUTBOX_COUNTS_AS_STOPPED;
    var attempted = mostAttemptsOfOneEntry(dataSource);
    while (attempted < attempts) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "expected an outbox entry attempted %d times but the most attempted one carries %d"
              .formatted(attempts, attempted));
      Thread.sleep(50);
      attempted = mostAttemptsOfOneEntry(dataSource);
    }

  }

  private static long mostAttemptsOfOneEntry(
      final DataSource dataSource) throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .createStatement(); var resultSet = statement.executeQuery(MOST_ATTEMPTS)) {
      resultSet.next();
      return resultSet.getLong(1);
    }

  }

  private static long entriesCarryingAnAttempt(
      final DataSource dataSource) throws Exception {

    try (var connection = dataSource.getConnection(); var statement = connection
        .createStatement(); var resultSet = statement.executeQuery(COUNT_ATTEMPTED)) {
      resultSet.next();
      return resultSet.getLong(1);
    }

  }

}
