package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.deployment.PlainIterationResolver;
import io.vanillabp.integration.test.deployment.PlainResolverWorkflowService;
import io.vanillabp.integration.test.deployment.ResolverAggregate;
import io.vanillabp.integration.test.deployment.ResolverAggregatePersistence;
import io.vanillabp.integration.test.deployment.ResolverProcessWiringSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Story 71: a resolver class which is no bean at all used to surface at the first
 * iteration of the task, one retry cycle at a time. Now the build says it, naming the
 * class and what it is used as.
 */
@ExtendWith(SuppressOutputExtension.class)
public class MultiInstanceResolverNoBeanTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("multi-instance-resolver/application.yaml", "application.yaml")
          .addClass(ResolverAggregate.class)
          .addClass(ResolverAggregatePersistence.class)
          .addClass(PlainResolverWorkflowService.class)
          .addClass(PlainIterationResolver.class)
          .addClass(ResolverProcessWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/ResolverProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          if ((current.getMessage() != null) && current.getMessage().contains("is a CDI bean")) {
            assertTrue(
                current.getMessage().contains(PlainIterationResolver.class.getName()),
                current.getMessage());
            assertTrue(current.getMessage().contains("resolverBean"), current.getMessage());
            return;
          }
          current = current.getCause();
        }
        fail("expected the build to name the resolver which is no bean but got: "
            + throwable);
      });

  @Test
  @DisplayName("A resolver which is no bean fails the build naming the class")
  public void resolverWhichIsNoBeanFailsTheBuild() {
    // the assertion happens on the build exception (assertException above)
  }

}
