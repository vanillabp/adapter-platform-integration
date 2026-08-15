package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.persistence.RepositoryAggregate;
import io.vanillabp.integration.test.persistence.RepositoryAggregateRepository;
import io.vanillabp.integration.test.persistence.RepositoryWorkflowService;
import io.vanillabp.integration.test.persistence.SecondRepositoryAggregateRepository;
import io.vanillabp.integration.test.persistence.SingleTaskWiringSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;

/**
 * Story 69: two repositories for one aggregate are not a coin flip. The build fails
 * and the message names both of them, so the developer either keeps one or writes
 * the {@code AggregatePersistenceAware} deciding it.
 */
@ExtendWith(SuppressOutputExtension.class)
public class AmbiguousRepositoriesTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(RepositoryAggregate.class)
          .addClass(RepositoryAggregateRepository.class)
          .addClass(SecondRepositoryAggregateRepository.class)
          .addClass(RepositoryWorkflowService.class)
          .addClass(SingleTaskWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TestProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .assertException(throwable -> {
        var current = throwable;
        while (current != null) {
          if ((current.getMessage() != null) && current.getMessage().contains("Several repositories manage")) {
            assertTrue(current.getMessage().contains(RepositoryAggregate.class.getName()), current.getMessage());
            assertTrue(
                current.getMessage().contains(RepositoryAggregateRepository.class.getName()),
                current.getMessage());
            assertTrue(
                current.getMessage().contains(SecondRepositoryAggregateRepository.class.getName()),
                current.getMessage());
            return;
          }
          current = current.getCause();
        }
        fail("expected the guiding message about several repositories but got: "
            + throwable);
      });

  @Test
  @DisplayName("Two repositories for one aggregate fail the build naming both")
  public void ambiguousRepositoriesFailTheBuild() {
    // the assertion happens on the build exception (assertException above)
  }

}
