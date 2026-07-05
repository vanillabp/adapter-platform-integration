package io.vanillabp.integration.test.utils;

import java.util.LinkedList;
import java.util.List;

/**
 * Helpers for collecting test coverage of code executed in forked JVMs (e.g. Quarkus
 * prod-mode tests): the JaCoCo agent configured for the build is passed on to the
 * forked JVM.
 */
public class TestCoverageUtils {

  public static List<String> testCoverageJavaAgent() {

    return testCoverageJavaAgent(new LinkedList<>());

  }

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
