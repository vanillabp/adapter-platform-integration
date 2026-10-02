package io.vanillabp.integration.test.upgradecost;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.bpmsdouble.DummyDeploymentService;
import io.vanillabp.integration.adapter.migration.config.PhaseTwoOutboxProperties;
import io.vanillabp.integration.adapter.migration.delivery.JdbcTaskDeliveryStore;
import io.vanillabp.integration.adapter.migration.outbox.DispatchLanes;
import io.vanillabp.integration.adapter.spi.workflowtask.TaskInvocationContext;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.spi.process.ProcessService;

/**
 * What an application pays for the upgrade from version 1, counted on the side which
 * changed.
 * <p>
 * Version 1 wrote no outbox row and kept no delivery log; neither class exists in any of
 * its artifacts, which is why the version 1 side of this comparison is read off those
 * artifacts and the version 2 side is counted here. What version 2 adds is a row in the
 * caller's transaction, a transaction of its own for the call which leaves, and a record per
 * task delivery. Those three are what this class pins down, in rows and in commits rather
 * than in milliseconds: a millisecond from a machine of today is read as a promise half a
 * year later.
 * <p>
 * The handler does nothing, on purpose. The time an application spends in its own handler is
 * the application's, and it moved from the engine's job transaction into a transaction
 * VanillaBP opens. That belongs in the documentation as a sentence, not in here as a number.
 * <p>
 * Commits are asserted and statements are not. A commit is a transaction an upgrader can count,
 * while the number of statements inside one is Hibernate's business and changes with it. The
 * statements measured on 2026-10-02 are written down rather than pinned: eight for a start in the
 * caller's thread, one for its dispatch, and five for a task delivery.
 * <p>
 * The widths the last test reads are the sizing answer for an upgrader. On Camunda 7 version
 * 1 progressed a workflow on the engine's job executor, three threads growing to ten with the
 * Spring Boot starter's defaults, and on Camunda 8 it sent from the thread which committed.
 * Version 2 dispatches on lanes of its own, as many as
 * <code>vanillabp.outbox.dispatch-threads</code>.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = TestApplication.class)
public class WhatAnUpgradeFromVersionOneCostsTest {

  private static final String MODULE = "upgrade-cost-module";

  private static final String ADAPTER = "the-bpms";

  /**
   * How long a test waits for a dispatch before it says none is coming. Nothing is measured
   * in that time, so the budget is only ever spent by a test which fails.
   */
  private static final long UNTIL_A_DISPATCH_COUNTS_AS_LOST = 30000;

  /**
   * How long one held call waits for the others to arrive beside it. Generous, because a
   * machine carrying other builds leaves this JVM without a turn for seconds at a time, and
   * what is read afterwards is how many arrived rather than how fast.
   */
  private static final long UNTIL_THE_OTHERS_COUNT_AS_ABSENT = 15000;

  @Autowired
  private ProcessService<CostAggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private DataSource dataSource;

  @Autowired
  private WhereTheDispatchRan dispatch;

  @Autowired
  private ApplicationContext context;

  private WhatTheDatabaseWasAskedFor meter;

  private JdbcTemplate jdbc;

  @BeforeEach
  public void startFromNothingCounted() throws Exception {

    meter = dataSource.unwrap(WhatTheDatabaseWasAskedFor.class);
    jdbc = new JdbcTemplate(dataSource);
    dispatch.startWatching();

  }

  /**
   * How many outbox entries this aggregate has, whatever state they are in.
   *
   * @param aggregate The aggregate asked about
   * @return Its entries
   */
  private long outboxEntriesOf(
      final CostAggregate aggregate) {

    return PhaseTwoOutboxReader
        .ofTheVanillaBpOutbox(dataSource)
        .entries()
        .stream()
        .filter(entry -> aggregate.getId().toString().equals(entry.aggregateId()))
        .count();

  }

  /**
   * How many of this aggregate's entries still have their dispatch before them.
   *
   * @param aggregate The aggregate asked about
   * @return Its waiting entries
   */
  private long entriesStillWaitingFor(
      final CostAggregate aggregate) {

    return PhaseTwoOutboxReader
        .ofTheVanillaBpOutbox(dataSource)
        .entries()
        .stream()
        .filter(entry -> aggregate.getId().toString().equals(entry.aggregateId()))
        .filter(PhaseTwoOutboxReader.Entry::isWaiting)
        .count();

  }

  /**
   * How many delivery records this aggregate has.
   *
   * @param aggregate The aggregate asked about
   * @return Its records
   */
  private int deliveryRecordsOf(
      final CostAggregate aggregate) {

    return jdbc
        .queryForObject(
            "SELECT COUNT(*) FROM %s WHERE AGGREGATE_ID = ?"
                .formatted(JdbcTaskDeliveryStore.DEFAULT_TABLE_NAME),
            Integer.class,
            aggregate.getId().toString());

  }

  /**
   * Starts one workflow the way an endpoint does: inside a transaction of the caller.
   *
   * @param content What the aggregate carries
   * @return The attached aggregate
   */
  private CostAggregate startedInOneTransaction(
      final String content) {

    return transactionTemplate.execute(status -> {
      final var aggregate = new CostAggregate();
      aggregate.setContent(content);
      return processService.startWorkflow(aggregate);
    });

  }

  /**
   * Waits until nothing of this aggregate is waiting any more.
   *
   * @param aggregate The aggregate whose entries have to be through
   */
  private void awaitNothingLeftUndone(
      final CostAggregate aggregate) throws Exception {

    final var deadline = System.currentTimeMillis() + UNTIL_A_DISPATCH_COUNTS_AS_LOST;
    while (entriesStillWaitingFor(aggregate) > 0) {
      assertTrue(
          System.currentTimeMillis() < deadline,
          "an entry of aggregate '%s' was never dispatched".formatted(aggregate.getId()));
      Thread.sleep(50);
    }

  }

  @Test
  @DisplayName("A start writes one outbox row in the caller's transaction and the caller commits once")
  public void aStartCostsOneRowAndOneCommit() throws Exception {

    meter.startCounting();
    final var caller = Thread.currentThread().getName();

    final var aggregate = startedInOneTransaction("one-start");

    assertEquals(
        1,
        meter.commitsOf(caller),
        "the caller committed %d times instead of once".formatted(meter.commitsOf(caller)));
    assertEquals(
        1,
        outboxEntriesOf(aggregate),
        "the start wrote %d outbox entries instead of one".formatted(outboxEntriesOf(aggregate)));
    assertEquals(
        0,
        deliveryRecordsOf(aggregate),
        "a start wrote a delivery record, which belongs to a delivery and not to a start");

    awaitNothingLeftUndone(aggregate);

    final var dispatchThreads = dispatch.differentThreads();
    assertEquals(
        1,
        dispatchThreads.size(),
        "one start was dispatched by %s".formatted(dispatchThreads));
    final var dispatchThread = dispatchThreads.iterator().next();
    assertFalse(
        caller.equals(dispatchThread),
        "the call left in the caller's own thread, which is what version 1 did");
    assertEquals(
        1,
        meter.commitsOf(dispatchThread),
        "the dispatch committed %d times, so the call did not cost one transaction of its own"
            .formatted(meter.commitsOf(dispatchThread)));

  }

  @Test
  @DisplayName("A task delivery writes one delivery record, which version 1 never wrote")
  public void aDeliveryCostsOneRecord() throws Exception {

    final var aggregate = startedInOneTransaction("one-delivery");
    awaitNothingLeftUndone(aggregate);

    meter.startCounting();
    final var deliveryThread = Thread.currentThread().getName();

    deliverTheTaskOf(aggregate, "delivery-1");

    assertEquals(
        1,
        deliveryRecordsOf(aggregate),
        "the delivery left %d records instead of one".formatted(deliveryRecordsOf(aggregate)));
    assertEquals(
        1,
        meter.commitsOf(deliveryThread),
        "the delivery committed %d times, so the record did not ride the handler's transaction"
            .formatted(meter.commitsOf(deliveryThread)));

    // the same delivery again is what a BPMS which never learned the result sends
    deliverTheTaskOf(aggregate, "delivery-1");

    assertEquals(
        1,
        deliveryRecordsOf(aggregate),
        "a repeated delivery wrote a second record");

  }

  /**
   * One delivery of this scenario's single task, as an adapter of a remote BPMS builds it.
   *
   * @param aggregate The workflow the task belongs to
   * @param deliveryId What stays the same when the BPMS repeats the delivery
   */
  private void deliverTheTaskOf(
      final CostAggregate aggregate,
      final String deliveryId) {

    context
        .getBean("DummyAdapter_DeploymentService_"
            + ADAPTER, DummyDeploymentService.class)
        .invokeTask(MODULE, CostWorkflowService.PROCESS, new TaskInvocationContext() {

          @Override
          public String getAdapterId() {

            return ADAPTER;

          }

          @Override
          public String getTaskDefinition() {

            return CostWorkflowService.TASK;

          }

          @Override
          public String getWorkflowAggregateId() {

            return aggregate.getId().toString();

          }

          @Override
          public String getDeliveryId() {

            return deliveryId;

          }

        });

  }

  @Test
  @DisplayName("The dispatch runs on as many threads as it is configured for, all at once")
  public void theDispatchIsAsWideAsItsLanes() throws Exception {

    // the default of the property rather than a number typed here: what the documentation
    // tells an upgrader to count into its connection pool has to be what the code does
    final var lanes = new PhaseTwoOutboxProperties().getDispatchThreads();
    assertEquals(
        4,
        lanes,
        "this measurement reads the default, and the default is %d".formatted(lanes));

    dispatch.holdEveryCallUntilThereAre(lanes, UNTIL_THE_OTHERS_COUNT_AS_ABSENT);

    // two starts per lane, so no lane is left without work while the others are held
    final var started = IntStream
        .range(0, lanes * 2)
        .mapToObj(number -> startedInOneTransaction("lane-"
            + number))
        .toList();
    final var lanesTheseAggregatesFallOn = started
        .stream()
        .map(aggregate -> DispatchLanes.laneOf(aggregate.getId().toString(), lanes))
        .distinct()
        .count();
    assertEquals(
        lanes,
        lanesTheseAggregatesFallOn,
        "the %d aggregates of this measurement fall on %d of the %d lanes, so the measurement cannot see the width"
            .formatted(started.size(), lanesTheseAggregatesFallOn, lanes));

    for (final var aggregate : started) {
      awaitNothingLeftUndone(aggregate);
    }

    assertEquals(
        lanes,
        dispatch.differentThreads().size(),
        "the calls arrived on %s, so they did not travel on %d threads"
            .formatted(dispatch.differentThreads(), lanes));
    assertEquals(
        started.size(),
        dispatch.threadsWhichBroughtACall().size(),
        "%d starts arrived as %d calls"
            .formatted(started.size(), dispatch.threadsWhichBroughtACall().size()));
    assertTrue(
        meter.threadsWhichCommitted().size() > 1,
        "everything committed in one thread: %s".formatted(meter.threadsWhichCommitted()));

  }

}
