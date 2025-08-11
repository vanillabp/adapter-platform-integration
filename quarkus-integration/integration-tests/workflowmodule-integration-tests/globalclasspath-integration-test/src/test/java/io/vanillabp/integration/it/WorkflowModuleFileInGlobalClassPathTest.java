package io.vanillabp.integration.it;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;

public class WorkflowModuleFileInGlobalClassPathTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addAsResource("META-INF/workflow-module"))
      // JVM args needed for tracking coverage. Check pom.xml for systemPropertyVariables
      .setJVMArgs(List.of("-javaagent:%s".formatted(System.getProperty("jacoco.agent"))));

  @Test
  public void testBuildCompletedWithoutError() {
    // nothing to test
  }

}