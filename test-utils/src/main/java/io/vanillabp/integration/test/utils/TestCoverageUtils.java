package io.vanillabp.integration.test.utils;

import java.util.LinkedList;
import java.util.List;

/**
 * Helpers which collect the test coverage of code run in forked JVMs, for example in
 * Quarkus prod-mode tests. They pass the JaCoCo agent configured for the build on to the
 * forked JVM.
 * <p>
 * Once it was doubted whether a forked run counts at all. JaCoCo drops execution data
 * whose class id does not match the class file it reports on. Quarkus rewrites classes
 * while it augments an application. And reports had shown the sentence
 * <code>A different version of class was executed at runtime</code>. If that hit the code
 * under measurement, a Quarkus number would only count what happens outside the forked
 * run, and nothing of the run itself.
 * <p>
 * This was measured in <code>camunda7-adapter</code> on 2026-09-18, with JaCoCo 0.8.15
 * and Quarkus 3.39.3. The class id of every entry in all six execution-data files of that
 * build was compared with the class file the report analyses. Quarkus does rewrite the
 * classes of the application it augments: 13 of the 17 classes of the Quarkus test
 * application there. But it leaves alone what arrives as a dependency, and the code under
 * measurement arrives that way. 99 classes of the adapter kept the id of their class
 * file. So did 151 classes of the migration adapter and 44 of the Quarkus integration,
 * seen inside the same forked application. The test application is not measured anyway,
 * because of the JaCoCo excludes in the root POM. That is why no report of that build
 * shows the sentence any more.
 * <p>
 * So a forked run counts, and there it counted for a lot. The Quarkus aggregate was at
 * 93.07 % of instructions, and at 84.56 % without the execution data of the prod-mode
 * module. That is what passing on the agent is worth. Without it, the run proves the
 * features but counts for nothing.
 */
public class TestCoverageUtils {

  /**
   * Nobody builds this class. It only answers static questions.
   */
  private TestCoverageUtils() {
  }

  /**
   * The JaCoCo agent of the build, as the only JVM argument.
   *
   * @return The argument list to hand to the forked JVM, empty when the build runs
   *         without coverage
   */
  public static List<String> testCoverageJavaAgent() {

    return testCoverageJavaAgent(new LinkedList<>());

  }

  /**
   * Adds the JaCoCo agent of the build to JVM arguments already collected.
   *
   * @param jvmArgs The arguments collected so far, e.g. from
   *          {@link TestJvmArgs#quarkusProdModeTestDefaults()}
   * @return The same list, with the agent appended, or unchanged when the build runs
   *         without coverage
   */
  public static List<String> testCoverageJavaAgent(
      final List<String> jvmArgs) {

    final var jacocoAgent = System.getProperty("jacoco.agent");
    if (jacocoAgent == null) {
      return jvmArgs;
    }

    jvmArgs.add(jacocoAgent);

    return jvmArgs;

  }

}
