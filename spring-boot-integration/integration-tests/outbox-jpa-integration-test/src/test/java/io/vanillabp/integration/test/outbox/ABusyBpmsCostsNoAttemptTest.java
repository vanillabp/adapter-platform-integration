package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader;
import io.vanillabp.integration.test.utils.outbox.PhaseTwoOutboxReader.Entry;
import io.vanillabp.spi.process.ProcessService;

/**
 * A BPMS under load refuses commands for a while (Camunda 8: backpressure, HTTP 503 with
 * <code>RESOURCE_EXHAUSTED</code>). The entry comes back after
 * <code>vanillabp.outbox.attempt-frequency</code>, and such a refusal uses no attempt of it. So
 * an entry which is fine is never blocked only because its BPMS was full. The double reports
 * its <code>DummyBusy</code> as such a refusal. The Quarkus side has the same test, against the
 * same store.
 * <p>
 * The proof is the attempt counter of the entry. The store counts every attempt it makes, the
 * one which went through as well. And each refusal writes a WARN line, because that line is
 * where an overloaded BPMS becomes visible in operation.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = TestApplication.class)
public class ABusyBpmsCostsNoAttemptTest {

  /**
   * How often the BPMS refuses the dispatch. More than one, so a store which counted the
   * refusals would show it in the counter.
   */
  private static final int REFUSALS = 3;

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
  @DisplayName("A busy BPMS uses no attempt of the entry, and each refusal is a WARN")
  public void aBusyBpmsUsesNoAttempt(
      final CapturedOutput output) throws Exception {

    listener.failNextDispatchesWithABusyBpms(REFUSALS);

    final var aggregate = startWorkflow("a-busy-bpms");
    final var entry = dispatchedEntryOf(aggregate.getId());

    assertEquals(REFUSALS + 1, invocationsOf(aggregate.getId()), "the refusals and the attempt which went through");
    assertEquals(
        1,
        entry.attempts(),
        "the store counted a refusal of the busy BPMS as a failed attempt");
    final var busyLines = output
        .getAllOfThisTest()
        .lines()
        .filter(line -> line.contains("met a busy BPMS"))
        .toList();
    assertEquals(REFUSALS, busyLines.size(), "one line per refusal: "
        + busyLines);
    assertTrue(
        busyLines
            .stream()
            .allMatch(line -> line.contains(" WARN ") && line.contains("adapter 'test'") && line
                .contains("uses none of its attempts")),
        "each refusal is a WARN which names the adapter: "
            + busyLines);

  }

}
