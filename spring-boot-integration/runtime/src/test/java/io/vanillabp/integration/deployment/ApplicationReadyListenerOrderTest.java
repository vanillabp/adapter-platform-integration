package io.vanillabp.integration.deployment;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.core.annotation.Order;

import io.vanillabp.integration.outbox.gruelbox.GruelboxPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.outbox.mongo.MongoPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Pins the {@code ApplicationReadyEvent} listener ordering contract:
 * workflow processing starts BEFORE the phase-two outbox dispatchers' pollers run
 * their first (crash-recovery) poll - so a recovered phase-two operation is never
 * dispatched before BPMN resources were deployed and workflow processing started.
 * The same invariant is enforced on Quarkus via {@code StartupEvent} observer
 * priorities.
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
    final var gruelboxDispatcher = listenerOrder(GruelboxPhaseTwoOutboxDispatcher.class, "startPolling");
    final var mongoDispatcher = listenerOrder(MongoPhaseTwoOutboxDispatcher.class, "startPolling");

    assertTrue(
        startProcessing < gruelboxDispatcher,
        "workflow processing must start BEFORE the gruelbox outbox dispatcher polls");
    assertTrue(
        startProcessing < mongoDispatcher,
        "workflow processing must start BEFORE the MongoDB outbox dispatcher polls");

  }

}
