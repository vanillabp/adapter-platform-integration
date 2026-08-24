package io.vanillabp.migration.test.observability;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;

import io.vanillabp.integration.adapter.migration.observability.DeliveryMdc;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * The logging context of a delivery: the keys are there while the work
 * runs, they are gone afterwards, and what the application put on the thread survives
 * both.
 */
@ExtendWith(SuppressOutputExtension.class)
public class DeliveryMdcTest {

  @AfterEach
  public void clearMdc() {

    MDC.clear();

  }

  @Test
  @DisplayName("A delivery sets the keys and gives the thread back as it found it")
  public void keysAreSetAndRestored() {

    MDC.put("application.key", "kept");
    MDC.put(DeliveryMdc.WORKFLOW_AGGREGATE_ID, "an-outer-value");

    try (var ignored = DeliveryMdc
        .ofTaskDelivery("c8", "module", "Process", "4711", "approve", "job-1")) {

      Assertions.assertEquals("c8", MDC.get(DeliveryMdc.ADAPTER));
      Assertions.assertEquals("module", MDC.get(DeliveryMdc.WORKFLOW_MODULE));
      Assertions.assertEquals("Process", MDC.get(DeliveryMdc.BPMN_PROCESS));
      Assertions.assertEquals("4711", MDC.get(DeliveryMdc.WORKFLOW_AGGREGATE_ID));
      Assertions.assertEquals("approve", MDC.get(DeliveryMdc.TASK_DEFINITION));
      Assertions.assertEquals("job-1", MDC.get(DeliveryMdc.DELIVERY_ID));
      Assertions.assertEquals("kept", MDC.get("application.key"));

    }

    Assertions
        .assertEquals(
            "an-outer-value",
            MDC.get(DeliveryMdc.WORKFLOW_AGGREGATE_ID),
            "a nested delivery gives the outer one its context back");
    Assertions.assertNull(MDC.get(DeliveryMdc.ADAPTER));
    Assertions.assertEquals("kept", MDC.get("application.key"));

  }

  @Test
  @DisplayName("A delivery which throws restores the context as well")
  public void keysAreRestoredOnTheFailurePath() {

    Assertions
        .assertThrows(
            IllegalStateException.class,
            () -> {
              try (var ignored = DeliveryMdc
                  .ofTaskDelivery("c8", "module", "Process", "4711", "approve", "job-1")) {
                throw new IllegalStateException("the handler threw");
              }
            });

    DeliveryMdc.KEYS.forEach(key -> Assertions.assertNull(MDC.get(key)));

  }

  @Test
  @DisplayName("A phase-two dispatch knows no task and says so")
  public void phaseTwoDispatchLeavesTheTaskKeysEmpty() {

    MDC.put(DeliveryMdc.TASK_DEFINITION, "left-over-from-somewhere");

    try (var ignored = DeliveryMdc.ofPhaseTwoDispatch("c8", "module", "Process", "4711")) {

      Assertions.assertEquals("c8", MDC.get(DeliveryMdc.ADAPTER));
      Assertions.assertEquals("4711", MDC.get(DeliveryMdc.WORKFLOW_AGGREGATE_ID));
      Assertions
          .assertNull(
              MDC.get(DeliveryMdc.TASK_DEFINITION),
              "an outbox entry belongs to no task, so the key has to be absent rather than stale");
      Assertions.assertNull(MDC.get(DeliveryMdc.DELIVERY_ID));

    }

    Assertions.assertEquals("left-over-from-somewhere", MDC.get(DeliveryMdc.TASK_DEFINITION));

  }

}
