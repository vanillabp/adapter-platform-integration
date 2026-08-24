package io.vanillabp.integration.runtime.test.deployment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.quarkus.runtime.StartupEvent;
import io.vanillabp.integration.runtime.deployment.VanillaBpDeploymentRunner;
import io.vanillabp.integration.runtime.outbox.JdbcPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.runtime.outbox.MongoPhaseTwoOutboxDispatcher;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.annotation.Priority;
import jakarta.enterprise.event.Observes;

/**
 * Pins the startup-observer ordering contract: the deployment runner
 * observes the {@link StartupEvent} with a LOWER priority value (= earlier) than
 * both phase-two outbox dispatchers, so a crash-recovered phase-two operation is
 * never dispatched before BPMN resources were deployed and workflow processing
 * started. The functional proof lives in the deployment integration tests
 * (OutboxRecoveryOrderingTest); this test fails fast if someone removes or reorders
 * the priorities.
 */
@ExtendWith(SuppressOutputExtension.class)
public class StartupObserverPriorityTest {

  private static int startupObserverPriority(
      final Class<?> observerClass) {

    final var observerMethod = Arrays
        .stream(observerClass.getDeclaredMethods())
        .filter(method -> method.getParameterCount() == 1)
        .filter(method -> method.getParameterTypes()[0].equals(StartupEvent.class))
        .filter(StartupObserverPriorityTest::observesStartup)
        .findFirst()
        .orElse(null);
    assertNotNull(observerMethod, "no StartupEvent observer found on "
        + observerClass.getName());
    final var priority = Arrays
        .stream(observerMethod.getParameterAnnotations()[0])
        .filter(Priority.class::isInstance)
        .map(Priority.class::cast)
        .findFirst()
        .orElse(null);
    assertNotNull(priority, "no @Priority on the StartupEvent observer of "
        + observerClass.getName());
    return priority.value();

  }

  private static boolean observesStartup(
      final Method method) {

    return Arrays
        .stream(method.getParameterAnnotations()[0])
        .anyMatch(Observes.class::isInstance);

  }

  @Test
  @DisplayName("The deployment runner starts before both outbox dispatchers")
  public void deploymentRunnerBeforeOutboxDispatchers() {

    final var runnerPriority = startupObserverPriority(VanillaBpDeploymentRunner.class);
    final var jdbcDispatcherPriority = startupObserverPriority(JdbcPhaseTwoOutboxDispatcher.class);
    final var mongoDispatcherPriority = startupObserverPriority(MongoPhaseTwoOutboxDispatcher.class);

    assertEquals(VanillaBpDeploymentRunner.STARTUP_PRIORITY, runnerPriority);
    assertTrue(
        runnerPriority < jdbcDispatcherPriority,
        "the deployment runner must observe the StartupEvent BEFORE the JDBC outbox dispatcher");
    assertTrue(
        runnerPriority < mongoDispatcherPriority,
        "the deployment runner must observe the StartupEvent BEFORE the MongoDB outbox dispatcher");

  }

}
