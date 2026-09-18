package io.vanillabp.integration.test.utils;

import java.util.LinkedList;
import java.util.List;

/**
 * Helpers for collecting test coverage of code executed in forked JVMs (e.g. Quarkus
 * prod-mode tests): the JaCoCo agent configured for the build is passed on to the
 * forked JVM.
 * <p>
 * Whether a forked run reaches the number at all was doubted once. JaCoCo drops execution
 * data whose class id does not match the class file it reports on, Quarkus rewrites
 * classes while it augments an application, and reports had carried the sentence
 * <code>A different version of class was executed at runtime</code>. If that hit the code
 * under measurement, a Quarkus number would say what happens outside the forked run and
 * nothing about the run itself.
 * <p>
 * It was measured in <code>camunda7-adapter</code> on 2026-09-18, with JaCoCo 0.8.15 and
 * Quarkus 3.39.3. The class id of every entry in all six execution-data files of that
 * build was compared with the class file the report analyses. Quarkus does rewrite the
 * classes of the application it augments, 13 of the 17 classes of the Quarkus test
 * application there. It leaves alone what arrives as a dependency, and the code under
 * measurement arrives that way: 99 classes of the adapter kept the id of their class
 * file, and so did 151 classes of the migration adapter and 44 of the Quarkus integration
 * seen inside the same forked application. The test application is excluded from the
 * measurement anyway, by the JaCoCo excludes in the root POM. That is why no report of
 * that build carries the sentence any more.
 * <p>
 * So a forked run counts, and there it counted for a lot. The Quarkus aggregate stood at
 * 93.07 % of instructions, and at 84.56 % with the execution data of the prod-mode module
 * left out. That is what forwarding the agent is worth. Forget it and the run proves the
 * features while counting as nothing.
 */
public class TestCoverageUtils {

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
