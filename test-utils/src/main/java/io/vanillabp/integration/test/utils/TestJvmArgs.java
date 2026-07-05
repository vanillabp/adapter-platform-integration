package io.vanillabp.integration.test.utils;

import java.util.LinkedList;
import java.util.List;

/**
 * Common JVM arguments for tests forking a JVM (e.g. Quarkus prod-mode tests).
 */
public class TestJvmArgs {

  public static List<String> quarkusProdModeTestDefaults() {

    return quarkusProdModeTestDefaults(new LinkedList<>());

  }

  public static List<String> quarkusProdModeTestDefaults(
      final List<String> jvmArgs) {

    jvmArgs.add("-Xmx192m"); // see QuarkusProdModeTest#jvmArgs

    return jvmArgs;

  }

}
