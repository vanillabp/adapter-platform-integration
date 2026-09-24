package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * The outbox repeats a failed dispatch, which is what makes losing a
 * concurrency conflict survivable. A failure the BPMS answers the same way every time
 * gains nothing from that, so an adapter may say
 * ({@code MigratableProcessService#isPhaseTwoFailureRepeatable}) that repeating cannot
 * help - the entry is then blocked after the first attempt instead of after the
 * configured ones.
 * <p>
 * The test runs on a database of its own: it blocks an entry on purpose, and a blocked
 * entry stays in the store for operations to find.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PermanentPhaseTwoFailureTest {

  /**
   * How long a test waits before it says that nothing more happened. The application
   * dispatches every <code>vanillabp.outbox.attempt-frequency</code>, which these tests
   * configure as half a second, so this is three of those windows.
   * <p>
   * It is a guard and not a measurement of speed: a machine which leaves this JVM without
   * a turn only makes the wait longer, and what is asserted afterwards is a count which
   * did not grow.
   */
  private static final long UNTIL_NOTHING_MORE_CAN_COME = 1500;

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(Aggregate.class)
          .addClass(AggregatePersistence.class)
          .addClass(WorkflowService.class)
          .addClass(RecordingPhaseTwoListener.class)
          .addClass(TestMeterRegistryProducer.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:outbox-permanent-it;DB_CLOSE_DELAY=-1");

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  DataSource dataSource;

  @Inject
  io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry;

  @BeforeEach
  public void resetListener() {

    listener.reset();

  }

  /**
   * The entries of ONE aggregate. A count over the whole table would already be
   * satisfied by a sibling test's entry.
   *
   * @param aggregateId The aggregate asked about
   * @return Its entries
   */
  private List<Entry> entriesOf(
      final Object aggregateId) {

    return PhaseTwoOutboxReader
        .ofTheVanillaBpOutbox(dataSource)
        .entries()
        .stream()
        .filter(entry -> aggregateId.toString().equals(entry.aggregateId()))
        .toList();

  }

  /**
   * @param aggregateId The aggregate asked about
   * @return How many of its entries were put aside
   */
  private long blockedEntriesOf(
      final Object aggregateId) {

    return entriesOf(aggregateId)
        .stream()
        .filter(Entry::isBlocked)
        .count();

  }

  /**
   * @param aggregateId The aggregate asked about
   * @return The highest number of attempts one of its entries carries
   */
  private long attemptsOf(
      final Object aggregateId) {

    return entriesOf(aggregateId)
        .stream()
        .mapToInt(Entry::attempts)
        .max()
        .orElse(0);

  }

  @Test
  @DisplayName("A failure repeating cannot fix blocks the entry after the first attempt")
  public void permanentFailureBlocksTheEntryImmediately() throws Exception {

    final var blockedEntriesCounted = blockedEntriesCounted();
    listener.failNextDispatchesPermanently(1);

    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("permanent-failure-test");
    userTransaction.commit();

    listener.awaitInvocations(1, 30_000);

    final var deadline = System.currentTimeMillis() + 30_000;
    while (blockedEntriesOf(attachedAggregate.getId()) == 0) {
      assertTrue(System.currentTimeMillis() < deadline, "the entry was not blocked");
      Thread.sleep(50);
    }

    // exactly one attempt, and nothing retries a blocked entry
    assertEquals(1, attemptsOf(attachedAggregate.getId()));
    Thread.sleep(UNTIL_NOTHING_MORE_CAN_COME);
    assertEquals(1, attemptsOf(attachedAggregate.getId()));
    assertEquals(
        1,
        listener
            .getInvocations()
            .stream()
            .filter(attachedAggregate.getId()::equals)
            .count(),
        "the dispatch must not be repeated");

    assertEquals(
        blockedEntriesCounted + 1.0,
        blockedEntriesCounted(),
        "a blocked entry is counted, because the gauge of waiting entries falls at that moment");

  }

  /**
   * The counter of blocked entries, or zero while nothing was blocked yet and the meter
   * does not exist. Read as a difference because the sibling test below blocks an entry
   * too and the order of the two is nobody's promise.
   *
   * @return How many entries of the JDBC store were blocked for a permanent failure
   */
  private double blockedEntriesCounted() {

    final var counter = meterRegistry
        .find(io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.OUTBOX_BLOCKED)
        .tag(
            io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.TAG_STORE,
            "JdbcPhaseTwoOutbox")
        .tag(
            io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.TAG_OPERATION,
            io.vanillabp.integration.spi.PhaseOperation.START_WORKFLOW.name())
        .tag(
            io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics.TAG_PERMANENT,
            "true")
        .counter();
    return counter == null
        ? 0.0
        : counter.count();

  }

  /**
   * A blocked entry used to hold its deduplication key, and the store refuses an
   * identical key for as long as an entry holds it - so the failed operation silenced
   * its own repetition, with an answer indistinguishable from a correct deduplication.
   * Blocking releases the key now, exactly as a dispatch releases it.
   */
  @Test
  @DisplayName("A blocked entry does not swallow the next attempt of the same operation")
  public void aBlockedEntryReleasesItsDeduplicationKey() throws Exception {

    listener.failNextDispatchesPermanently(1);

    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("blocked-releases-key");
    userTransaction.commit();

    listener.awaitInvocations(1, 30_000);
    final var deadline = System.currentTimeMillis() + 30_000;
    while (blockedEntriesOf(attachedAggregate.getId()) == 0) {
      assertTrue(System.currentTimeMillis() < deadline, "the entry was not blocked");
      Thread.sleep(50);
    }

    // the same operation, planned again while the blocked row still sits there
    userTransaction.begin();
    workflowService.startWorkflowAgain(attachedAggregate);
    userTransaction.commit();

    // it reaches the BPMS: a second entry was written and dispatched, and the blocked
    // one stays where it is for whoever repairs it
    assertEquals(
        2,
        listener
            .awaitInvocations(2, 30_000)
            .stream()
            .filter(attachedAggregate.getId()::equals)
            .count(),
        "the repetition of a blocked operation was discarded");
    assertEquals(1, blockedEntriesOf(attachedAggregate.getId()));
    assertEquals(2, entriesOf(attachedAggregate.getId()).size());

  }

  /**
   * The counter-check: a failure the adapter reports as repeatable is still repeated,
   * so the blocking above is the adapter's answer and not a change of the store's
   * behaviour.
   */
  @Test
  @DisplayName("A repeatable failure is still retried instead of being blocked")
  public void repeatableFailureIsRetried() throws Exception {

    listener.failNextDispatches(1);

    userTransaction.begin();
    final var attachedAggregate = workflowService.startWorkflow("repeatable-failure-test");
    userTransaction.commit();

    final var invocations = listener.awaitInvocations(2, 30_000);
    assertEquals(attachedAggregate.getId(), invocations.get(0));
    assertEquals(attachedAggregate.getId(), invocations.get(1));
    assertEquals(0, blockedEntriesOf(attachedAggregate.getId()));

  }

}
