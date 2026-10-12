package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.Logger;

import org.jboss.logmanager.formatters.PatternFormatter;
import org.jboss.logmanager.handlers.ConsoleHandler;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import io.vanillabp.integration.test.utils.CapturedOutput;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * What a test logs through JBoss LogManager is captured like everything else.
 * <p>
 * Redirecting <code>System.out</code> alone never reached it. The Quarkus test modules
 * install that log manager (Surefire property <code>java.util.logging.manager</code>). A
 * handler of it holds the <code>System.out</code> which existed when the handler was
 * created. That is why the augmentation line of a prod-mode test, and every Testcontainers
 * line of the Camunda 8 Quarkus tests, appeared in the log of a GREEN build.
 * <p>
 * The handler below is created in a static initializer. So it holds the real
 * <code>System.out</code> before any extension callback runs. That is exactly the
 * situation the defect needs. Remove the redirection from the extension, and this test
 * fails, and the marker is printed to the console instead.
 * <p>
 * On purpose it asserts the capture and not the silence. A console handler which is
 * switched off would break the capture. And a failing class has to replay what the
 * application logged.
 */
@ExtendWith(SuppressOutputExtension.class)
public class JbossLogManagerCaptureTest {

  private static final ConsoleHandler HANDLER_HOLDING_THE_REAL_STDOUT;

  static {
    HANDLER_HOLDING_THE_REAL_STDOUT = new ConsoleHandler(new PatternFormatter("%s%n"));
    HANDLER_HOLDING_THE_REAL_STDOUT.setLevel(Level.ALL);
    LogManager.getLogManager().getLogger("").addHandler(HANDLER_HOLDING_THE_REAL_STDOUT);
  }

  @AfterAll
  public static void removeTheHandlerAgain() {

    LogManager.getLogManager().getLogger("").removeHandler(HANDLER_HOLDING_THE_REAL_STDOUT);

  }

  @Test
  @DisplayName("A line logged through JBoss LogManager reaches the captured output, not the console")
  public void aLineLoggedThroughJbossLogManagerIsCaptured(
      final CapturedOutput captured) {

    // without this, the test would pass for the wrong reason. Under the JDK's own log
    // manager, the capture works through System.out and proves nothing about this
    assertTrue(
        LogManager.getLogManager().getClass().getName().startsWith("org.jboss.logmanager."),
        () -> "This module has to run under JBoss LogManager, but the installed manager is "
            + LogManager.getLogManager().getClass().getName());

    Logger.getLogger(getClass().getName()).log(Level.SEVERE, "jboss-log-manager-marker");

    assertTrue(
        captured.getAll().contains("jboss-log-manager-marker"),
        () -> "The line went past the capture, so it reached the console: "
            + captured.getAll());
    // This absence stays on the view of the whole class on purpose. Nothing logs
    // "never-logged-marker", here or anywhere else, so no neighbour can put it into the
    // buffer. The wide view then proves more: the marker is nowhere in the class, not
    // only nowhere in this test.
    assertFalse(
        captured.getAll().contains("never-logged-marker"),
        "the capture reports what was logged, nothing else");

  }

}
