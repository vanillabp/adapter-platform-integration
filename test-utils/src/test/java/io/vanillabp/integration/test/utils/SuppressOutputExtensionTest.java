package io.vanillabp.integration.test.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * What the extension promises: a green build says nothing, a red one says why. The two
 * classes at the bottom are run through a launcher of their own, because both halves of that
 * promise depend on the order in which the classes of one fork run, and that order is what a
 * single test class cannot show.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SuppressOutputExtensionTest {

  private static final String WHAT_THE_FAILING_TEST_PRINTED = "the reason this test went red";

  private static final String WHAT_A_BACKGROUND_THREAD_PRINTS = "a container shutting down";

  @Test
  @DisplayName("A failing class is readable behind a class which silenced its background output")
  public void aFailingClassIsReadableBehindASilencingClass() {

    final var console = new ByteArrayOutputStream();

    final var failingRun = withTheConsoleReplacedBy(console, () -> {
      run(SilencesItsBackgroundOutput.class);
      return run(FailsAfterPrinting.class);
    });

    assertEquals(1, failingRun.getTotalFailureCount(), "the second class was expected to fail");
    assertTrue(
        readAll(console).contains(WHAT_THE_FAILING_TEST_PRINTED),
        () -> """
            A class which silenced its background output ran first, and the failure of the class \
            after it was replayed into the silenced stream instead of into the console, so a red \
            build states no reason. This is everything the console got:
            %s"""
            .formatted(readAll(console)));

  }

  @Test
  @DisplayName("A failing class is readable when it runs first in its fork")
  public void aFailingClassIsReadableOnItsOwn() {

    final var console = new ByteArrayOutputStream();

    final var failingRun = withTheConsoleReplacedBy(console, () -> run(FailsAfterPrinting.class));

    assertEquals(1, failingRun.getTotalFailureCount(), "the class was expected to fail");
    assertTrue(
        readAll(console).contains(WHAT_THE_FAILING_TEST_PRINTED),
        "the failing class replayed nothing at all");

  }

  @Test
  @DisplayName("A class which silenced its background output keeps the console quiet afterwards")
  public void aSilencingClassKeepsTheConsoleQuiet() {

    final var console = new ByteArrayOutputStream();

    withTheConsoleReplacedBy(console, () -> {
      final var silencingRun = run(SilencesItsBackgroundOutput.class);
      // what a thread of that class prints once the class itself has finished
      System.out.println(WHAT_A_BACKGROUND_THREAD_PRINTS);
      System.err.println(WHAT_A_BACKGROUND_THREAD_PRINTS);
      return silencingRun;
    });

    assertFalse(
        readAll(console).contains(WHAT_A_BACKGROUND_THREAD_PRINTS),
        "the silenced streams let output through again, so a green build is noisy from here on");

  }

  /**
   * Runs classes with the console replaced by a buffer, which is what makes the replay of a
   * failing class assertable: the extension writes that replay to whatever stood in for the
   * console when the class started capturing.
   *
   * @param console Where the replay is expected to arrive
   * @param classes The launcher runs, in the order the classes would run in one fork
   * @return The summary of the last run
   */
  private static TestExecutionSummary withTheConsoleReplacedBy(
      final ByteArrayOutputStream console,
      final Supplier<TestExecutionSummary> classes) {

    final var outBeforeThisTest = System.out;
    final var errBeforeThisTest = System.err;
    final var consoleStream = new PrintStream(console, true, StandardCharsets.UTF_8);
    System.setOut(consoleStream);
    System.setErr(consoleStream);
    try {
      return classes.get();
    } finally {
      System.setOut(outBeforeThisTest);
      System.setErr(errBeforeThisTest);
    }

  }

  private static TestExecutionSummary run(
      final Class<?> testClass) {

    final var request = LauncherDiscoveryRequestBuilder
        .request()
        .selectors(DiscoverySelectors.selectClass(testClass))
        .build();
    final var listener = new SummaryGeneratingListener();
    LauncherFactory
        .create()
        .execute(request, listener);
    return listener.getSummary();

  }

  private static String readAll(
      final ByteArrayOutputStream console) {

    return console.toString(StandardCharsets.UTF_8);

  }

  /**
   * Stands for the test classes of the adapters which carry the annotation because their
   * containers keep printing after the class has finished.
   */
  @ExtendWith(SuppressOutputExtension.class)
  @SuppressBackgroundOutput
  static class SilencesItsBackgroundOutput {

    @Test
    public void passes() {

      System.out.println("what a passing class prints and nobody needs to read");

    }

  }

  /** The class whose failure a red build is supposed to explain. */
  @ExtendWith(SuppressOutputExtension.class)
  static class FailsAfterPrinting {

    @Test
    public void fails() {

      System.out.println(WHAT_THE_FAILING_TEST_PRINTED);
      throw new IllegalStateException("this failure is what the tests above measure");

    }

  }

}
