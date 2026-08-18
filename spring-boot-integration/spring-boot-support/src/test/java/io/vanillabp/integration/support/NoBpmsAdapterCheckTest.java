package io.vanillabp.integration.support;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Story 81: an application which has a workflow module but no BPMS adapter hears it from
 * VanillaBP, and hears which artifacts would give it one.
 * <p>
 * The two facts the rule is made of cannot be varied inside a running JVM (a class either
 * is on the classpath or is not), so they are handed to {@link NoBpmsAdapterCheck#verify}
 * directly. What the JVM of this module DOES represent is the case the message was written
 * for - no VanillaBP integration on the classpath, a workflow-module marker in the test
 * resources - and the boot of that application is asserted as a whole.
 */
@ExtendWith(SuppressOutputExtension.class)
public class NoBpmsAdapterCheckTest {

  @Test
  @DisplayName("A workflow module without an adapter ends the boot naming the artifacts to add")
  public void aWorkflowModuleWithoutAnAdapterEndsTheBoot() {

    final var failure = assertThrowsExactly(
        IllegalStateException.class,
        () -> NoBpmsAdapterCheck.verify(false, true));

    assertTrue(
        failure.getMessage().contains("No VanillaBP BPMS adapter found in classpath!"),
        failure.getMessage());
    assertTrue(
        failure.getMessage().contains("META-INF/workflow-module"),
        failure.getMessage());
    assertTrue(
        failure
            .getMessage()
            .contains("org.camunda.community.vanillabp:camunda7-adapter-spring-boot"),
        failure.getMessage());
    assertTrue(
        failure
            .getMessage()
            .contains("org.camunda.community.vanillabp:camunda8-adapter-spring-boot"),
        failure.getMessage());
    assertTrue(
        failure.getMessage().contains("io.vanillabp:process-engine-api-adapter-spring-boot"),
        failure.getMessage());
    // adapters are released on their own schedule, so the BOM does not manage their version
    assertTrue(failure.getMessage().contains("BOM"), failure.getMessage());

  }

  @Test
  @DisplayName("The neighbouring cases keep their own messages")
  public void theNeighbouringCasesAreLeftAlone() {

    // an adapter brought the integration: the integration reports a missing adapter itself,
    // and it knows the adapters actually loaded
    assertDoesNotThrow(() -> NoBpmsAdapterCheck.verify(true, true));
    assertDoesNotThrow(() -> NoBpmsAdapterCheck.verify(true, false));
    // no workflow module: nothing to run, so nothing to report (the smoke test of every
    // blueprint boots exactly this application)
    assertDoesNotThrow(() -> NoBpmsAdapterCheck.verify(false, false));

  }

  @Test
  @DisplayName("The check runs before the first bean of the application is created")
  public void theCheckRunsBeforeTheFirstBean() {

    // the classpath of this module IS the case of the message: a workflow-module marker in
    // the test resources, and no VanillaBP integration anywhere
    assertTrue(
        NoBpmsAdapterCheck
            .anyWorkflowModuleInClasspath(NoBpmsAdapterCheckTest.class.getClassLoader()));

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(VanillaBpSupportAutoConfiguration.class))
        .withBean("aBeanOfTheApplication", Object.class, Object::new)
        .run(context -> {
          assertTrue(context.getStartupFailure() != null, "expected the boot to end");
          assertTrue(
              stackTraceOf(context.getStartupFailure())
                  .contains("No VanillaBP BPMS adapter found in classpath!"),
              stackTraceOf(context.getStartupFailure()));
        });

  }

  private static String stackTraceOf(
      final Throwable failure) {

    final var stringWriter = new java.io.StringWriter();
    failure.printStackTrace(new java.io.PrintWriter(stringWriter));
    return stringWriter.toString();

  }

}
