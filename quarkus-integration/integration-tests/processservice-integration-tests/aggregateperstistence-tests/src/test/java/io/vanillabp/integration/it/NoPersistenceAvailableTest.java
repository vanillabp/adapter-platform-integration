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
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.intergration.test.utils.SuppressOutputExtension;

@ExtendWith(SuppressOutputExtension.class)
public class NoPersistenceAvailableTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(WorkflowService.class)
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
                You have to provide a CDI bean implementing
                  io.vanillabp.spi.process.AggregatePersistenceAware
                which is responsible to persist aggregates.
                This is necessary because in Quarkus there is no unique way to do persistence of entities:
                - Active record pattern: https://quarkus.io/guides/hibernate-orm-panache#solution-1-using-the-active-record-pattern
                - Repository record pattern: https://quarkus.io/guides/hibernate-orm-panache#solution-2-using-the-repository-pattern
                - Spring Data pattern: https://quarkus.io/guides/spring-data-jpa""",
            rootCause.getMessage());
      });

  @Test
  public void testBuildFailedWithExpectedError() {
    fail("Should have failed at build time");
  }

}
