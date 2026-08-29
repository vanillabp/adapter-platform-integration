package io.vanillabp.integration.test.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
@SpringBootTest(classes = TestApplication.class)
@ExtendWith(SuppressOutputExtension.class)
@SuppressOutputExtension.SuppressBackgroundOutput
public class OutboxMetricsTest {

  @Autowired
  private ProcessService<Aggregate> processService;

  @Autowired
  private TransactionTemplate transactionTemplate;

  @Autowired
  private RecordingPhaseTwoListener listener;

  @Autowired
  private MicrometerVanillaBpMetrics metrics;

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

  }

}
