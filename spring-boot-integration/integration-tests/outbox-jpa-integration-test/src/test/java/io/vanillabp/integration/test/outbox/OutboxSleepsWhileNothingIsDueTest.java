package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.ResolvableType;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.IMetricsTracker;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;
import io.vanillabp.spi.process.ProcessService;

/**
 * What a quiet application costs on the JDBC store, counted in connections, not measured
 * in seconds. A poll is a few database commands, whether or not anything is waiting. An
 * application which waited in a timer used to pay for them every ten seconds.
 * <p>
 * The test counts connections, not statements, because a connection is the lower bound.
 * If none was taken, none was used, whatever the code would have sent over it. The pool
 * reports every one of them.
 * <p>
 * The test does not claim that the poller sleeps. It claims that the database is not
 * touched while nothing is due. Each test boots its own application on its own database,
 * because each test sets a different cap on the sleep.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class OutboxSleepsWhileNothingIsDueTest {

  // one database per test, for the reason OutboxRecoveryTest gives
  private static final String DATASOURCE_URL_PATTERN = "jdbc:h2:mem:outbox-sleeping-%s;DB_CLOSE_DELAY=-1";

  /**
   * How often a connection was taken out of the pool. Hikari reports this to any tracker it
   * is given. So this test watches the pool of the application, not a pool of its own.
   */
  private final java.util.concurrent.atomic.AtomicLong connectionsTaken = new java.util.concurrent.atomic.AtomicLong();

  /**
   * Starts counting what the application takes out of its pool.
   *
   * @param dataSource The application's data source
   */
  private void countConnectionsOf(
      final javax.sql.DataSource dataSource) {

    final IMetricsTracker tracker = new IMetricsTracker() {
      @Override
      public void recordConnectionAcquiredNanos(
          final long elapsedAcquiredNanos) {
        connectionsTaken.incrementAndGet();
      }
    };
    ((HikariDataSource) dataSource).setMetricsTrackerFactory((
        poolName,
        poolStats) -> tracker);

  }

  private ConfigurableApplicationContext runApplication(
      final String database,
      final String longestSleep) {

    return new SpringApplicationBuilder(TestApplication.class)
        .web(WebApplicationType.NONE)
        .run(
            "--spring.datasource.url="
                + DATASOURCE_URL_PATTERN.formatted(database),
            "--vanillabp.outbox.poll-interval="
                + longestSleep,
            "--vanillabp.outbox.attempt-frequency=PT0.5S");

  }

  private Aggregate startAWorkflow(
      final ConfigurableApplicationContext context,
      final String content) {

    @SuppressWarnings("unchecked")
    final var processService = (ProcessService<Aggregate>) context
        .getBeanProvider(ResolvableType.forClassWithGenerics(ProcessService.class, Aggregate.class))
        .getObject();
    final var transactionTemplate = context.getBean(TransactionTemplate.class);
    final var attached = transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent(content);
      return processService.startWorkflow(aggregate);
    });
    assertNotNull(attached);
    return attached;

  }

  /**
   * Waits until the outbox has dispatched everything it holds. These tests start counting
   * from this state of "nothing is due".
   *
   * @param dataSource Where the outbox table lives
   */
  private void awaitNothingLeftUndone(
      final DataSource dataSource) throws Exception {

    final var deadline = System.currentTimeMillis() + 30000;
    while (outboxTableOf(dataSource).entriesWaiting() > 0) {
      assertTrue(System.currentTimeMillis() < deadline, "an entry of the outbox was never dispatched");
      Thread.sleep(50);
    }

  }

  /**
   * How long the store is watched after it went quiet. Three seconds are long enough for a
   * poller with its own rhythm to come back at least once. The shortest rhythm here is the
   * half second of <code>vanillabp.outbox.attempt-frequency</code>.
   * <p>
   * Nobody has to be faster than this number. Afterwards the test asserts that nothing was
   * asked at all. A machine which leaves this JVM without a turn only makes the silence
   * longer.
   */
  private static final long SILENCE_MEASURED_OVER_MS = 3000;

  /**
   * How long the pool must stay untouched before the work counts as over. It is below
   * {@link #SILENCE_MEASURED_OVER_MS}. So the wait catches a poller which asks on a
   * shorter rhythm, instead of letting it pass.
   */
  private static final long QUIET_FOR_MS = 500;

  /**
   * Waits until the poll which brought the store into the state above has stopped taking
   * connections.
   * <p>
   * The poll does not end when nothing is left to dispatch. It looks for the next due
   * entry, deletes what the retention lets go, and reads when to wake up again. These
   * statements run AFTER the wait above has returned. If the counter were set back to zero
   * in between, a test like this would sometimes turn red on a run where nothing was
   * wrong.
   * <p>
   * The wait only reads the counter and asks the database nothing. A question of its own
   * would be exactly the traffic it waits out. These tests are about a store which never
   * goes quiet. So the deadline says that, instead of timing out without a word.
   */
  private void awaitTheWorkWentQuiet() throws Exception {

    final var deadline = System.currentTimeMillis() + 30000;
    var takenSeen = connectionsTaken.get();
    var quietSince = System.currentTimeMillis();
    while ((System.currentTimeMillis() - quietSince) < QUIET_FOR_MS) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "connections were taken over and over while nothing was due");
      Thread.sleep(20);
      final var takenNow = connectionsTaken.get();
      if (takenNow != takenSeen) {
        takenSeen = takenNow;
        quietSince = System.currentTimeMillis();
      }
    }

  }

  /**
   * @param dataSource The database of the application under test
   * @return What its outbox table holds
   */
  private PhaseTwoOutboxReader outboxTableOf(
      final DataSource dataSource) {

    return PhaseTwoOutboxReader.ofTheVanillaBpOutbox(dataSource);

  }

  @Test
  @DisplayName("No connection is taken while the store owes nothing")
  public void aQuietStoreIsAskedNothing() throws Exception {

    try (var context = runApplication("quiet", "PT1H")) {
      final var dataSource = context.getBean(DataSource.class);
      countConnectionsOf(dataSource);
      startAWorkflow(context, "quiet-store");
      awaitNothingLeftUndone(dataSource);
      // the zero below is a measurement, and not just silence, only if the counter sees
      // connections at all
      assertTrue(connectionsTaken.get() > 0, "the pool has to report what this test itself took");

      awaitTheWorkWentQuiet();
      connectionsTaken.set(0);
      Thread.sleep(SILENCE_MEASURED_OVER_MS);

      assertEquals(
          0L,
          connectionsTaken.get(),
          "a store with nothing to do must not take a connection, and a connection is what every "
              + "statement needs");
    }

  }

  @Test
  @DisplayName("A blocked entry does not keep the poller awake")
  public void aBlockedEntryIsNotSomethingToWakeUpFor() throws Exception {

    try (var context = runApplication("blocked", "PT1H")) {
      final var dataSource = context.getBean(DataSource.class);
      final var listener = context.getBean(RecordingPhaseTwoListener.class);
      countConnectionsOf(dataSource);
      // a failure the adapter calls permanent is blocked at the first attempt. That is the
      // shortest way to an entry which waits for a person, not for a clock
      listener.failNextDispatchesPermanently(Integer.MAX_VALUE);
      startAWorkflow(context, "blocked-entry");

      final var deadline = System.currentTimeMillis() + 30000;
      while (outboxTableOf(dataSource)
          .entries()
          .stream()
          .noneMatch(Entry::isBlocked)) {
        assertTrue(System.currentTimeMillis() < deadline, "the entry was never blocked");
        Thread.sleep(50);
      }
      assertTrue(connectionsTaken.get() > 0, "the pool has to report what this test itself took");

      awaitTheWorkWentQuiet();
      connectionsTaken.set(0);
      Thread.sleep(SILENCE_MEASURED_OVER_MS);

      assertEquals(
          0L,
          connectionsTaken.get(),
          "an entry nobody can dispatch until it is repaired must not be asked about");
    }

  }

  @Test
  @DisplayName("An entry another node left behind is dispatched within the cap")
  public void theCapPicksUpWhatAnotherNodeLeftBehind() throws Exception {

    // nothing tells a sleeping node about an entry another node wrote. Where no shared
    // cache reports that the other node went away, the cap covers it
    try (var context = runApplication("cap", "PT2S")) {
      final var dataSource = context.getBean(DataSource.class);
      final var listener = context.getBean(RecordingPhaseTwoListener.class);
      startAWorkflow(context, "capped-sleep");
      awaitNothingLeftUndone(dataSource);
      assertEquals(1, listener.getInvocations().size());

      // the row is opened again, as the wiki tells an operator to do. From here this looks
      // like another node which inserts an entry: a due row nobody told this node about
      outboxTableOf(dataSource).openEveryEntryAgain();

      final var deadline = System.currentTimeMillis() + 30000;
      while (listener.getInvocations().size() < 2) {
        assertTrue(
            System.currentTimeMillis() < deadline,
            "the reopened entry was not dispatched within the cap");
        Thread.sleep(50);
      }
    }

  }

}
