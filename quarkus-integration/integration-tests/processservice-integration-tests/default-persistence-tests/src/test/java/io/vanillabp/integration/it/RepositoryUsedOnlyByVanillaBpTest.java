package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.persistence.RepositoryAggregate;
import io.vanillabp.integration.test.persistence.RepositoryAggregateRepository;
import io.vanillabp.integration.test.persistence.RepositoryWorkflowService;
import io.vanillabp.integration.test.persistence.SingleTaskWiringSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;

/**
 * An application whose repository is used by VanillaBP alone injects it
 * nowhere. Quarkus removes beans nobody injects while building the application, and
 * although the Panache extension keeps repositories itself today, VanillaBP looks this
 * one up by class and marks it unremovable rather than relying on that. The aggregate
 * is read back through the entity manager here - injecting the repository would be
 * exactly the injection point this test must not have.
 */
@ExtendWith(SuppressOutputExtension.class)
public class RepositoryUsedOnlyByVanillaBpTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(RepositoryAggregate.class)
          .addClass(RepositoryAggregateRepository.class)
          .addClass(RepositoryWorkflowService.class)
          .addClass(SingleTaskWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TestProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"));

  @Inject
  RepositoryWorkflowService workflowService;

  @Inject
  EntityManager entityManager;

  @Test
  @DisplayName("The repository survives the build although only VanillaBP uses it")
  public void repositoryNothingInjectsStillServesThePersistence() {

    final var started = QuarkusTransaction
        .requiringNew()
        .call(() -> workflowService.startWorkflow("only-vanillabp"));
    assertNotNull(started);

    final var stored = QuarkusTransaction
        .requiringNew()
        .call(() -> entityManager.find(RepositoryAggregate.class, "only-vanillabp"));
    assertNotNull(stored, "the aggregate was not stored - the repository bean did not survive the build");
    assertEquals("started", stored.getStatus());

  }

}
