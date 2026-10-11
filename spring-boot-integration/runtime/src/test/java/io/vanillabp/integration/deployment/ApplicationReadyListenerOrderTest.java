package io.vanillabp.integration.deployment;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.annotation.Order;

import io.vanillabp.integration.outbox.jdbc.JdbcPhaseTwoOutbox;
import io.vanillabp.integration.outbox.mongo.MongoPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Fixes the order of the {@code ApplicationReadyEvent} listeners. Workflow processing starts
 * BEFORE the pollers of the phase-two outbox dispatchers run their first poll, which recovers
 * after a crash. So a recovered phase-two operation is never dispatched before the BPMN
 * resources are deployed and workflow processing has started. On Quarkus, the priorities of
 * the {@code StartupEvent} observers ensure the same order.
 */
@ExtendWith(SuppressOutputExtension.class)
public class ApplicationReadyListenerOrderTest {

  private static int listenerOrder(
      final Class<?> listenerClass,
      final String methodName) throws NoSuchMethodException {

    final var order = listenerClass
        .getMethod(methodName)
        .getAnnotation(Order.class);
    assertNotNull(order, "no @Order on %s.%s".formatted(listenerClass.getSimpleName(), methodName));
    return order.value();

  }

  @Test
  @DisplayName("Workflow processing starts before both outbox dispatchers poll")
  public void startProcessingBeforeOutboxDispatchers() throws Exception {

    final var startProcessing = listenerOrder(SpringBootDeploymentService.class, "startProcessingOfWorkflows");
    final var jdbcDispatcher = listenerOrder(JdbcPhaseTwoOutbox.class, "startPolling");
    final var mongoDispatcher = listenerOrder(MongoPhaseTwoOutboxDispatcher.class, "startPolling");

    assertTrue(
        startProcessing < jdbcDispatcher,
        "workflow processing must start BEFORE the JDBC outbox dispatcher polls");
    assertTrue(
        startProcessing < mongoDispatcher,
        "workflow processing must start BEFORE the MongoDB outbox dispatcher polls");

  }

}
