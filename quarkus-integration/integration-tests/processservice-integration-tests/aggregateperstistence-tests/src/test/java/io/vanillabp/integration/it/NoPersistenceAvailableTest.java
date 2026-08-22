package io.vanillabp.integration.it;

import static io.vanillabp.integration.test.utils.TestCoverageUtils.testCoverageJavaAgent;
import static io.vanillabp.integration.test.utils.TestJvmArgs.quarkusProdModeTestDefaults;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusProdModeTest;
import io.vanillabp.integration.test.Aggregate;
import io.vanillabp.integration.test.WorkflowService;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

@ExtendWith(SuppressOutputExtension.class)
public class NoPersistenceAvailableTest {

  @RegisterExtension
  static final QuarkusProdModeTest prodModeTest = new QuarkusProdModeTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(WorkflowService.class)
          .addClass(Aggregate.class)
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      // JVM args needed for tracking coverage. Check pom.xml for <systemPropertyVariables> tag
      .setJVMArgs(testCoverageJavaAgent(quarkusProdModeTestDefaults()))
      .assertBuildException(exception -> {
        final var rootCause = org.junit.platform.commons.util.ExceptionUtils
            .findNestedThrowables(exception)
            .getLast();
        assertInstanceOf(IllegalStateException.class, rootCause);
        assertEquals(
            """
                VanillaBP does not know how to persist the workflow aggregate 'io.vanillabp.integration.test.Aggregate' of workflow module 'test-module'!
                Either the aggregate uses one of the persistence idioms VanillaBP serves out of the box:
                - a Panache repository for the aggregate (PanacheRepository/PanacheRepositoryBase, or PanacheMongoRepository/PanacheMongoRepositoryBase): https://quarkus.io/guides/hibernate-orm-panache#solution-2-using-the-repository-pattern
                - the aggregate itself being a Panache active record (extending PanacheEntity/PanacheEntityBase, or PanacheMongoEntity/PanacheMongoEntityBase): https://quarkus.io/guides/hibernate-orm-panache#solution-1-using-the-active-record-pattern
                - a Spring Data repository for the aggregate (extension quarkus-spring-data-jpa): https://quarkus.io/guides/spring-data-jpa
                None of them was found for this aggregate (mind that classes of workflow modules have to be indexed using the jandex-maven-plugin to be seen).
                Or, for any other kind of persistence, provide a CDI bean implementing
                  io.vanillabp.integration.spi.AggregatePersistenceAware<Aggregate>
                which is responsible to persist this aggregate.""",
            rootCause.getMessage());
      });

  @Test
  public void testBuildFailedWithExpectedError() {
    fail("Should have failed at build time");
  }

}
