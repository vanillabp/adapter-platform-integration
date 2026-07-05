package io.vanillabp.integration.test.utils;

import java.util.LinkedList;
import java.util.List;

public class TestCoverageUtils {

  public static List<String> testCoverageJavaAgent() {

    return testCoverageJavaAgent(new LinkedList<>());

  }

  public static List<String> quarkusProdModeTestDefaults() {

    return quarkusProdModeTestDefaults(new LinkedList<>());

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

  public static List<String> quarkusProdModeTestDefaults(
      final List<String> jvmArgs) {

    jvmArgs.add("-Xmx192m"); // see QuarkusProdModeTest#jvmArgs

    return jvmArgs;

  }

}
