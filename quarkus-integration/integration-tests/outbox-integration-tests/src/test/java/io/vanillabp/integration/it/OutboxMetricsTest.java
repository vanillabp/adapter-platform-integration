package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.adapter.migration.observability.VanillaBpMetrics;
import io.vanillabp.integration.spi.PhaseTwoOperation;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.AggregatePersistence;
import io.vanillabp.integration.test.RecordingPhaseTwoListener;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.transaction.UserTransaction;

/**
 * What the phase-two outbox reports about itself (story 92): the entries waiting to
 * be dispatched are a gauge, and every dispatch is counted. Those are the numbers an
 * operator looks at when a BPMS is unreachable - the outbox is where a broken
 * connection piles up first.
 */
@ExtendWith(SuppressOutputExtension.class)
public class OutboxMetricsTest {

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
      .overrideRuntimeConfigKey("quarkus.datasource.jdbc.url", "jdbc:h2:mem:outbox-metrics-it;DB_CLOSE_DELAY=-1");

  @Inject
  WorkflowService workflowService;

  @Inject
  RecordingPhaseTwoListener listener;

  @Inject
  UserTransaction userTransaction;

  @Inject
  SimpleMeterRegistry meterRegistry;

  @Test
  @DisplayName("The waiting entries are a gauge and every dispatch is counted")
  public void outboxReportsItsBacklogAndItsDispatches() throws Exception {

    listener.reset();

    final var pending = meterRegistry
        .get(VanillaBpMetrics.OUTBOX_PENDING)
        .gauge();
    assertNotNull(pending, "a store which can count its waiting entries publishes the gauge");
    assertEquals(
        0.0,
        pending.value(),
        "nothing was scheduled yet, so nothing is waiting");

    userTransaction.begin();
    workflowService.startWorkflow("metrics-test");
    userTransaction.commit();

    listener.awaitInvocations(1, 30_000);

    assertTrue(
        meterRegistry
            .get(VanillaBpMetrics.OUTBOX_DISPATCHES)
            .tag(VanillaBpMetrics.TAG_OPERATION, PhaseTwoOperation.START_WORKFLOW.name())
            .counter()
            .count() >= 1.0,
        "the dispatch of the started workflow's phase two has to be counted");

  }

}
