package io.vanillabp.integration.test.utils;

import java.util.LinkedList;
import java.util.List;

/**
 * Common JVM arguments for tests which fork a JVM, for example Quarkus prod-mode tests.
 */
public class TestJvmArgs {

  /**
   * Nobody builds this class. It only answers static questions.
   */
  private TestJvmArgs() {
  }

  /**
   * The JVM arguments a forked test starts from. A test passes the result to the fork it
   * configures. So every fork of every repository runs under the same limits.
   *
   * @return A new list, which the caller may add to
   */
  public static List<String> quarkusProdModeTestDefaults() {

    return quarkusProdModeTestDefaults(new LinkedList<>());

  }

  /**
   * The same defaults, added to arguments a test already collected. This variant chains
   * with {@link TestCoverageUtils#testCoverageJavaAgent(List)}. So a fork gets the limits
   * and the coverage agent in one call.
   *
   * @param jvmArgs The arguments collected so far. This list is changed
   * @return The same list, with the defaults appended
   */
  public static List<String> quarkusProdModeTestDefaults(
      final List<String> jvmArgs) {

    jvmArgs.add("-Xmx192m"); // see QuarkusProdModeTest#jvmArgs
    // A time zone, because the housekeeping window of the outbox is read in one (decision 91
    // of adapter-platform-integration). A forked application inherits neither the zone of the
    // runner nor the argument which the surefire configuration gives the test JVM. Without
    // this it would run on UTC and warn that nobody gave it a zone.
    jvmArgs.add("-Duser.timezone=Europe/Vienna");

    return jvmArgs;

  }

}
