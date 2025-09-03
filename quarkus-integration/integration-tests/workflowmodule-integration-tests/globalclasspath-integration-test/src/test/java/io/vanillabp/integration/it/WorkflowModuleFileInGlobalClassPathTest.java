package io.vanillabp.integration.it;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.vanillabp.intergration.test.utils.SuppressOutputExtension;

@ExtendWith(SuppressOutputExtension.class)
public class WorkflowModuleFileInGlobalClassPathTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addAsResource("META-INF/workflow-module"))
      // JVM args needed for tracking coverage. Check pom.xml for systemPropertyVariables
      .setJVMArgs(List.of(System.getProperty("jacoco.agent")));

  @Test
  public void testBuildCompletedWithoutError() {
    // nothing to test
  }

}