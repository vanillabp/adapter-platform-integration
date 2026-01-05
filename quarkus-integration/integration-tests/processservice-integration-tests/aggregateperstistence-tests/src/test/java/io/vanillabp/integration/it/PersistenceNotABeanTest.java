package io.vanillabp.integration.it;

import static io.vanillabp.intergration.test.utils.TestCoverageUtils.quarkusProdModeTestDefaults;
import static io.vanillabp.intergration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.NoBeanAggregatePersistence;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.intergration.test.utils.SuppressOutputExtension;
import io.vanillabp.spi.process.AggregatePersistenceAware;

@ExtendWith(SuppressOutputExtension.class)
public class PersistenceNotABeanTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(WorkflowService.class)
          .addClass(NoBeanAggregatePersistence.class)
          .addClass(Aggregate.class)
          .addAsResource("META-INF/workflow-module"))
      // JVM args needed for tracking coverage. Check pom.xml for <systemPropertyVariables> tag
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .assertBuildException(exception -> {
        final var rootCause = org.junit.platform.commons.util.ExceptionUtils
            .findNestedThrowables(exception)
            .getLast();
        assertInstanceOf(IllegalStateException.class, rootCause);
        assertEquals(
            """
                Class
                  %s
                was found by the VanillaBP extension as a
                  Service implementing the interface %s
                but neither the class itself nor any implementation is a CDI bean.
                Please annotate it with a bean-defining annotation such as @ApplicationScoped."""
                .formatted(NoBeanAggregatePersistence.class.getName(), AggregatePersistenceAware.class.getName()),
            rootCause.getMessage());
      });

  @Test
  public void testBuildFailedWithExpectedError() {
    fail("Should have failed at build time");
  }

}
