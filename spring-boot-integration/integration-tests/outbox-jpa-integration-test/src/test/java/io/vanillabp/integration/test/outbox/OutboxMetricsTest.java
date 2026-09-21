package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import io.vanillabp.spi.process.ProcessService;

/**
 * What the phase-two outbox reports about itself on Spring Boot. gruelbox
 * has no API for its backlog, so the number is read off its own table - which is why
 * this test runs against the real store rather than a double.
 */
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
@SpringBootTest(classes = TestApplication.class)
public class OutboxMetricsTest {

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @Autowired
  private MicrometerVanillaBpMetrics metrics;

  @Autowired
  private io.vanillabp.integration.spi.PhaseTwoOutbox outbox;

  @Test
  @DisplayName("The waiting entries are a gauge and every dispatch is counted")
  public void outboxReportsItsBacklogAndItsDispatches() throws Exception {

    listener.reset();

    final var registry = new SimpleMeterRegistry();
    metrics.bindTo(registry);

    final var pending = registry
        .get(VanillaBpMetrics.OUTBOX_PENDING)
        .tag(VanillaBpMetrics.TAG_STORE, "GruelboxPhaseTwoOutbox")
        .gauge();
    assertEquals(
        0.0,
        pending.value(),
        "gruelbox marks a dispatched entry processed, so nothing of the earlier tests is waiting");

    transactionTemplate
        .execute(status -> {
          final var aggregate = new Aggregate();
          aggregate.setContent("metrics-test");
          return processService.startWorkflow(aggregate);
        });

    listener.awaitInvocations(1, 30_000);

    assertTrue(
        registry
            .get(VanillaBpMetrics.OUTBOX_DISPATCHES)
            .tag(VanillaBpMetrics.TAG_OPERATION, PhaseOperation.START_WORKFLOW.name())
            .counter()
            .count() >= 1.0,
        "the dispatch of the started workflow's phase two has to be counted");

    awaitMeasuredWait(registry);

  }

  @Test
  @DisplayName("gruelbox publishes no age of its oldest waiting entry, because it keeps no such moment")
  public void gruelboxDoesNotSayHowOldItsOldestWaitingEntryIs() {

    final var registry = new SimpleMeterRegistry();
    metrics.bindTo(registry);

    assertTrue(
        outbox
            .ageOfOldestPendingCall()
            .isEmpty(),
        "gruelbox overwrites the moment an entry was written the first time it picks it up");
    assertNull(
        registry
            .find(VanillaBpMetrics.OUTBOX_OLDEST_PENDING_AGE)
            .tag(VanillaBpMetrics.TAG_STORE, "GruelboxPhaseTwoOutbox")
            .gauge(),
        "a gauge which could only ever report a gap is not published at all");

  }

  /**
   * Waits until the wait of the dispatched entry was measured.
   * <p>
   * The listener runs INSIDE the dispatch and the wait is reported when the dispatch
   * returns, so the listener is not the signal that the measurement is there. Waiting
   * for the meter is.
   *
   * @param registry The registry the meters were bound to
   */
  private void awaitMeasuredWait(
      final SimpleMeterRegistry registry) throws Exception {

    final var deadline = System.currentTimeMillis() + 30_000;
    while (true) {
      final var waited = registry
          .find(VanillaBpMetrics.OUTBOX_DISPATCH_LAG)
          .tag(VanillaBpMetrics.TAG_STORE, "GruelboxPhaseTwoOutbox")
          .tag(VanillaBpMetrics.TAG_OUTCOME, "succeeded")
          .timer();
      if ((waited != null) && (waited.count() >= 1L)) {
        return;
      }
      assertTrue(
          System.currentTimeMillis() < deadline,
          "the wait of the dispatched entry was never measured");
      Thread.sleep(50);
    }

  }

}
