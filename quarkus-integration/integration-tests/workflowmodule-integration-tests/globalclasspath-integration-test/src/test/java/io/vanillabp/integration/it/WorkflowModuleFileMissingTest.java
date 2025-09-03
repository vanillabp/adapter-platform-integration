package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.vanillabp.integration.test.sample.SampleWorkflowService;
import io.vanillabp.intergration.test.utils.SuppressOutputExtension;

@ExtendWith(SuppressOutputExtension.class)
public class WorkflowModuleFileMissingTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml"))
      // JVM args needed for tracking coverage. Check pom.xml for <systemPropertyVariables> tag
      .setJVMArgs(List.of(System.getProperty("jacoco.agent")))
      .assertBuildException(exception -> {
        final var rootCause = org.junit.platform.commons.util.ExceptionUtils
            .findNestedThrowables(exception)
            .getLast();
        assertInstanceOf(IllegalStateException.class, rootCause);
        assertEquals(
            """
                No workflow module descriptor 'META-INF/workflow-module' was found in any valid location:
                  - in JAR/directory of class '%s'
                  - in JAR/directory of Java module (if defined) of class '%s'
                  - in global classpath""".formatted(SampleWorkflowService.class.getName(),
                SampleWorkflowService.class.getName()),
            rootCause.getMessage());
      });

  @Test
  public void testBuildFailedWithExpectedError() {
    fail("Should have failed at build time");
  }

}