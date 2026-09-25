package io.vanillabp.integration.test.utils;

import java.util.LinkedList;
import java.util.List;

/**
 * Common JVM arguments for tests forking a JVM (e.g. Quarkus prod-mode tests).
 */
public class TestJvmArgs {

  /**
   * Nobody builds this class, it only answers static questions.
   */
  private TestJvmArgs() {
  }

  /**
   * The JVM arguments a forked test starts from. A test passes the result to the fork
   * it configures, so every fork of every repository runs under the same limits.
   *
   * @return A new list, which the caller may add to
   */
  public static List<String> quarkusProdModeTestDefaults() {

    return quarkusProdModeTestDefaults(new LinkedList<>());

  }

  /**
   * The same defaults, added to arguments a test collected already. This is the half
   * which chains with {@link TestCoverageUtils#testCoverageJavaAgent(List)}, so a fork
   * gets the limits and the coverage agent in one call.
   *
   * @param jvmArgs The arguments collected so far - this list is changed
   * @return The same list, with the defaults appended
   */
  public static List<String> quarkusProdModeTestDefaults(
      final List<String> jvmArgs) {

    jvmArgs.add("-Xmx192m"); // see QuarkusProdModeTest#jvmArgs
    // a time zone, because VanillaBP refuses to start a JVM which stands on UTC without one
    // being configured for the housekeeping of its outbox (decision 91 of
    // adapter-platform-integration). A forked application inherits neither the zone of the
    // runner nor the argument the surefire configuration gives the test JVM
    jvmArgs.add("-Duser.timezone=Europe/Vienna");

    return jvmArgs;

  }

}
