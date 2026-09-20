package io.vanillabp.integration.test.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.TestInstancePostProcessor;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;

import io.vanillabp.integration.test.utils.SuppressOutputExtension.SuppressBackgroundOutput;

/**
 * What the extension promises: a green build says nothing, a red one says why. The classes
 * at the bottom are run through a launcher of their own, because both halves of that
 * promise depend on the order in which the classes and the tests of one fork run, and that
 * order is what a single test class cannot show.
 * <p>
 * A build narrowed down with {@code -Dtest} runs those classes on their own, and their
 * failure then reads like a defect. Narrow a build down with {@code -pl} or {@code -rf}
 * instead. {@code CONTRIBUTING.md} says why the filter behaves that way.
 */
@ExtendWith(SuppressOutputExtension.class)
public class SuppressOutputExtensionTest {

  private static final String WHAT_THE_FAILING_TEST_PRINTED = "the reason this test went red";

  private static final String WHAT_A_BACKGROUND_THREAD_PRINTS = "a container shutting down";

  private static final String WHAT_THE_STARTUP_PRINTED = "the application context coming up";

  private static final String WHAT_THE_PASSING_TEST_PRINTED = "what the only test of the class printed";

  private static final String WHAT_WAS_PRINTED_AFTER_THE_LAST_TEST = "the last word of the class";

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

  @Test
  @DisplayName("A failing test shows what its class printed before the first test ran")
  public void aFailingTestShowsWhatWasPrintedBeforeTheFirstTest() {

    final var console = new ByteArrayOutputStream();

    final var failingRun = withTheConsoleReplacedBy(console, () -> run(StartsAContextAndFails.class));

    assertEquals(1, failingRun.getTotalFailureCount(), "the class was expected to fail");
    assertTrue(
        readAll(console).contains(WHAT_THE_STARTUP_PRINTED),
        () -> """
            An application context comes up before the first test, the way a @SpringBootTest \
            builds it, and the failing test replayed nothing of it, so the red build hides the \
            lines which say why the context is the way it is. This is everything the console got:
            %s"""
            .formatted(readAll(console)));
    final var whereTheStartupIs = readAll(console).indexOf(WHAT_THE_STARTUP_PRINTED);
    final var whereTheTestIs = readAll(console).indexOf(WHAT_THE_FAILING_TEST_PRINTED);
    assertTrue(
        whereTheStartupIs < whereTheTestIs,
        "what came up before the first test is expected at the top of the block, "
            + "in the order in which it was printed");

  }

  @Test
  @DisplayName("A passing class keeps what it printed before its first test to itself")
  public void aPassingClassSaysNothingAboutWhatItPrintedBeforeItsFirstTest() {

    final var console = new ByteArrayOutputStream();

    final var passingRun = withTheConsoleReplacedBy(
        console, () -> run(PrintsBeforeItsFirstTestAndPasses.class));

    assertEquals(0, passingRun.getTotalFailureCount(), "the class was expected to pass");
    assertFalse(
        readAll(console).contains(WHAT_THE_STARTUP_PRINTED),
        "a green run printed what happened before its first test, so green builds are noisy now");

  }

  @Test
  @DisplayName("A class failing after its last test shows everything it printed, once")
  public void aClassFailingAfterItsLastTestShowsWhatItPrinted() {

    final var console = new ByteArrayOutputStream();

    final var failingRun = withTheConsoleReplacedBy(
        console, () -> run(PrintsAfterItsLastTestAndFails.class));

    assertEquals(1, failingRun.getTotalFailureCount(), "the class was expected to fail");
    assertTrue(
        readAll(console).contains(WHAT_WAS_PRINTED_AFTER_THE_LAST_TEST),
        () -> """
            Nothing of what the class printed after its last test reached the console, \
            although that is where an afterAll says why it went wrong. This is everything \
            the console got:
            %s"""
            .formatted(readAll(console)));
    assertEquals(
        1,
        occurrencesIn(readAll(console), WHAT_THE_PASSING_TEST_PRINTED),
        "the output of the last test is expected once, not once more for the class it belongs to");

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

  private static int occurrencesIn(
      final String console,
      final String text) {

    var count = 0;
    var found = console.indexOf(text);
    while (found > -1) {
      ++count;
      found = console.indexOf(text, found + text.length());
    }
    return count;

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

  /**
   * A class whose application context comes up before its first test and which then goes
   * red.
   */
  @ExtendWith(SuppressOutputExtension.class)
  @ExtendWith(StandsInForTheSpringExtension.class)
  static class StartsAContextAndFails {

    @Test
    public void fails() {

      System.out.println(WHAT_THE_FAILING_TEST_PRINTED);
      throw new IllegalStateException("this failure is what the test above measures");

    }

  }

  /**
   * Prints where a {@code @SpringBootTest} prints the start of its application context:
   * when the instance of the first test is built, after the suppression started for the
   * class and before the first test begins.
   * <p>
   * Spring itself would be the better stand-in, but the {@code spring-test} on this
   * module's classpath needs a newer JUnit than this module builds against, and its
   * extension fails before it starts a context. The extension under test depends on the
   * window, not on who fills it.
   */
  static class StandsInForTheSpringExtension implements TestInstancePostProcessor {

    @Override
    public void postProcessTestInstance(
        final Object testInstance,
        final ExtensionContext context) {

      System.out.println(WHAT_THE_STARTUP_PRINTED);

    }

  }

  /** A green class prints nothing, not even what happened before its first test. */
  @ExtendWith(SuppressOutputExtension.class)
  static class PrintsBeforeItsFirstTestAndPasses {

    @BeforeAll
    public static void printsBeforeTheFirstTest() {

      System.out.println(WHAT_THE_STARTUP_PRINTED);

    }

    @Test
    public void passes(
        final CapturedOutput output) {

      assertTrue(
          output.getAll().contains(WHAT_THE_STARTUP_PRINTED),
          "a test asking for the captured output was not given what its class printed "
              + "before the first test began");

    }

  }

  /** What is printed after the last test belongs to the class, not to that test. */
  @ExtendWith(SuppressOutputExtension.class)
  static class PrintsAfterItsLastTestAndFails {

    @Test
    public void passes() {

      System.out.println(WHAT_THE_PASSING_TEST_PRINTED);

    }

    @AfterAll
    public static void printsAfterTheLastTestAndFails() {

      System.out.println(WHAT_WAS_PRINTED_AFTER_THE_LAST_TEST);
      throw new IllegalStateException("this failure is what the test above measures");

    }

  }

}
