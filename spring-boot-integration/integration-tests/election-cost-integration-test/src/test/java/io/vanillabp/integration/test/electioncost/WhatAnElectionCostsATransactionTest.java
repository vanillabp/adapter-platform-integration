package io.vanillabp.integration.test.electioncost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import com.zaxxer.hikari.HikariDataSource;

import io.vanillabp.integration.extension.spi.election.WorkflowElection;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Measures what the election costs a caller which holds a transaction. We measure it
 * instead of arguing about it.
 * <p>
 * An extension asks {@code WorkflowElection#adapterIdOfWorkflow} before it talks to a
 * BPMS about a workflow. The Business Cockpit asks it for every report of a service
 * task. That report needs an open transaction, because its entry belongs to the
 * transaction which wrote the change. The election asks with the patience of a read.
 * Say the read model of the BPMS is behind, and a hint names this adapter for the
 * workflow. Then the election asks again and again until the window of the adapter is
 * used up. The thread which holds the transaction is the thread which waits.
 * <p>
 * The tests measure two things. First, how long the transaction is open: the window of
 * the adapter plus the probes. Second, what a few such callers leave for everybody
 * else: nothing. Each of them holds a connection while it sleeps, and the pool belongs
 * to the whole application.
 * <p>
 * The tests use a short window, so a build does not pay for the measurement.
 * {@code migration-adapter/README.md} has the numbers for the window a Camunda 8
 * adapter really reports, from a run with ten seconds.
 */
@ExtendWith(SuppressOutputExtension.class)
@SpringBootTest(classes = TestApplication.class)
public class WhatAnElectionCostsATransactionTest {

  private static final String MODULE = "election-cost-module";

  private static final String PROCESS = "CostProcess";

  /**
   * The window the double reports while a test runs. It is short enough for a build. And
   * it is long enough that the measurement is more than the rounding of the clock.
   */
  private static final Duration WINDOW = Duration.ofMillis(1500);

  /**
   * What the pool holds, as configured in this scenario's {@code application.yaml}.
   */
  private static final int POOL_SIZE = 4;

  /**
   * How long a caller waits for a connection before it is refused, as configured there.
   */
  private static final Duration CONNECTION_TIMEOUT = Duration.ofMillis(1000);

  @Autowired
  private WorkflowElection election;

  @Autowired
  private ALaggingReadModel readModel;

  @Autowired
  private CostAggregateRepository repository;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private DataSource dataSource;

  /**
   * A workflow the election has seen once. That leaves the hint behind. Without a hint
   * nothing waits at all, so every measurement here starts with one.
   *
   * @return The id of its aggregate
   */
  private Long aWorkflowTheElectionKnows() {

    final var aggregate = new CostAggregate();
    aggregate.setContent("elected");
    final var saved = repository.saveAndFlush(aggregate);
    readModel.showTheWorkflow();
    assertEquals(
        "the-bpms",
        election.adapterIdOfWorkflow(MODULE, PROCESS, saved.getId()));
    return saved.getId();

  }

  /**
   * A report out of a service task, in its usual shape. A transaction is open, the
   * aggregate was read in it, and the election is asked while it is still open.
   *
   * @param workflowAggregateId The workflow to ask about
   * @return How long the transaction was open
   */
  private Duration aReportInsideATransaction(
      final Long workflowAggregateId) {

    final var startedAt = System.nanoTime();
    try {
      transactionTemplate.execute(status -> {
        // takes the connection, the way VanillaBP takes one for the aggregate of a task
        repository.findById(workflowAggregateId);
        return election.adapterIdOfWorkflow(MODULE, PROCESS, workflowAggregateId);
      });
    } catch (final IllegalStateException e) {
      // the election throws this when the window is used up and no adapter knows the
      // workflow. The wait happened anyway, and the caller pays for it
    }
    return Duration.ofNanos(System.nanoTime() - startedAt);

  }

  @Test
  @DisplayName("A report out of a service task holds the transaction for the whole visibility window")
  public void aReportHoldsTheTransactionForTheWholeWindow() {

    final var workflowAggregateId = aWorkflowTheElectionKnows();
    readModel.forgetTheWorkflow(WINDOW);

    final var held = aReportInsideATransaction(workflowAggregateId);

    assertTrue(
        held.compareTo(WINDOW) >= 0,
        "the transaction was open for %s, which is less than the window of %s the adapter reported"
            .formatted(held, WINDOW));
    // the first probe, then one per interval until the window is used up. The walk
    // after that skips the adapter it already asked
    final var expectedProbes = 1 + (WINDOW.toMillis() / ALaggingReadModel.PROBE_INTERVAL.toMillis());
    assertTrue(
        readModel.probes() >= expectedProbes,
        "the adapter was asked %d times, fewer than the %d the window and the interval say"
            .formatted(readModel.probes(), expectedProbes));

  }

  @Test
  @DisplayName("As many reports as the pool has connections leave nothing for anybody else")
  public void concurrentReportsEmptyTheConnectionPool() throws Exception {

    final var workflowAggregateId = aWorkflowTheElectionKnows();
    readModel.forgetTheWorkflow(WINDOW);

    final var pool = dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
    final var reportsAreWaiting = new CountDownLatch(POOL_SIZE);
    final var executor = Executors.newFixedThreadPool(POOL_SIZE);
    final var held = new ArrayList<Duration>();
    try {
      for (var report = 0; report < POOL_SIZE; ++report) {
        executor.submit(() -> {
          reportsAreWaiting.countDown();
          final var duration = aReportInsideATransaction(workflowAggregateId);
          synchronized (held) {
            held.add(duration);
          }
        });
      }
      assertTrue(
          reportsAreWaiting.await(10, TimeUnit.SECONDS),
          "the reports never started");
      // the reports take their connections one JDBC call after the latch. So the test
      // reads the pool until it shows what the latch cannot show
      final var poolIsEmptyBy = System.nanoTime() + Duration.ofSeconds(10).toNanos();
      while ((pool.getActiveConnections() < POOL_SIZE) && (System.nanoTime() < poolIsEmptyBy)) {
        Thread.sleep(10);
      }
      assertEquals(
          POOL_SIZE,
          pool.getActiveConnections(),
          "the reports did not take every connection of the pool");

      final var askedAt = System.nanoTime();
      final var refusal = assertThrows(
          SQLException.class,
          () -> dataSource.getConnection(),
          "a caller which has nothing to do with that BPMS still got a connection");
      final var waited = Duration.ofNanos(System.nanoTime() - askedAt);

      assertTrue(
          waited.compareTo(CONNECTION_TIMEOUT) >= 0,
          "the refusal came after %s, which is less than the configured %s"
              .formatted(waited, CONNECTION_TIMEOUT));
      assertTrue(
          refusal.getMessage().contains("Connection is not available"),
          "the pool refused for another reason: %s".formatted(refusal.getMessage()));
    } finally {
      executor.shutdown();
      assertTrue(
          executor.awaitTermination(30, TimeUnit.SECONDS),
          "the reports never finished");
    }

    assertEquals(POOL_SIZE, held.size());
    held.forEach(
        duration -> assertTrue(
            duration.compareTo(WINDOW) >= 0,
            "a report held its transaction for %s, less than the window of %s"
                .formatted(duration, WINDOW)));

  }

}
