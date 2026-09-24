package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vanillabp.integration.adapter.migration.observability.MicrometerVanillaBpMetrics;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.spi.PhaseOperation;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;
import io.vanillabp.spi.process.ProcessService;

/**
 * The outbox repeats a failed dispatch, which is what makes losing a concurrency conflict
 * survivable. A failure the BPMS answers the same way every time gains nothing from that,
 * so an adapter may say ({@code MigratableProcessService#isPhaseTwoFailureRepeatable})
 * that repeating cannot help, and the entry is then blocked after the first attempt.
 * <p>
 * The store here is the JDBC one, which is the default of a Spring Boot application on
 * JPA. The Quarkus side has the same test, against the same store.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = TestApplication.class)
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

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private DataSource dataSource;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @Autowired
  private MicrometerVanillaBpMetrics metrics;

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

  private Aggregate startWorkflow(
      final String content) {

    return transactionTemplate.execute(status -> {
      final var aggregate = new Aggregate();
      aggregate.setContent(content);
      return processService.startWorkflow(aggregate);
    });

  }

  @Test
  @DisplayName("A failure repeating cannot fix blocks the entry after the first attempt")
  public void permanentFailureBlocksTheEntryImmediately() throws Exception {

    final var registry = new SimpleMeterRegistry();
    metrics.bindTo(registry);
    listener.failNextDispatchesPermanently(1);

    final var attachedAggregate = startWorkflow("permanent-failure-test");

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
        1.0,
        registry
            .get(VanillaBpMetrics.OUTBOX_BLOCKED)
            .tag(VanillaBpMetrics.TAG_STORE, "JdbcPhaseTwoOutbox")
            .tag(VanillaBpMetrics.TAG_OPERATION, PhaseOperation.START_WORKFLOW.name())
            .tag(VanillaBpMetrics.TAG_PERMANENT, "true")
            .counter()
            .count(),
        "a blocked entry is counted, because the gauge of waiting entries falls at that moment");

  }

  /**
   * The counter-check: a failure the adapter reports as repeatable is still repeated, so
   * the blocking above is the adapter's answer and not a change of the store's behaviour.
   */
  @Test
  @DisplayName("A repeatable failure is still retried instead of being blocked")
  public void repeatableFailureIsRetried() throws Exception {

    listener.failNextDispatches(1);

    final var attachedAggregate = startWorkflow("repeatable-failure-test");

    final var invocations = listener.awaitInvocations(2, 30_000);
    assertEquals(attachedAggregate.getId(), invocations.get(0));
    assertEquals(attachedAggregate.getId(), invocations.get(1));
    assertEquals(0, blockedEntriesOf(attachedAggregate.getId()));

  }

}
