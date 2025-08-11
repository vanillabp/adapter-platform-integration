package io.vanillabp.integration.it;


import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;

public class WorkflowModuleFileInSubModuleTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml"))
      .setJVMArgs(List.of("-javaagent:%s".formatted(System.getProperty("jacoco.agent")))); // needed for tracking coverage

  @Test
  public void testBuildCompletedWithoutError() {
    // nothing to test
  }

}