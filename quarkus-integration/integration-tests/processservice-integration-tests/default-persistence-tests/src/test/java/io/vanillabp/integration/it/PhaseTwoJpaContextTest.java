package io.vanillabp.integration.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.RegisterExtension;

import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import io.vanillabp.integration.test.persistence.ActiveTaskAwarenessSource;
import io.vanillabp.integration.test.persistence.PhaseTwoRecorder;
import io.vanillabp.integration.test.persistence.RepositoryAggregate;
import io.vanillabp.integration.test.persistence.RepositoryAggregateRepository;
import io.vanillabp.integration.test.persistence.RepositoryWorkflowService;
import io.vanillabp.integration.test.persistence.SingleTaskWiringSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Phase two runs on the outbox dispatcher's own thread and calls back into
 * the application - a remote BPMS adapter loads the aggregate to build what it sends
 * to the BPMS. On Quarkus that thread had neither a transaction nor an active CDI
 * request context, so an aggregate stored with an entity manager could not be read:
 * the dispatch failed with a {@code ContextNotActiveException} and was retried until
 * the entry was blocked, while the application looked healthy (the API had answered,
 * the aggregate was in the database) and the workflow never appeared in the BPMS.
 * <p>
 * The dummy adapter is forced into the two-phase start here and reads the aggregate
 * like a remote BPMS does. Both dispatched operations of a JPA-backed application are
 * covered: starting the workflow and completing a task.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PhaseTwoJpaContextTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(RepositoryAggregate.class)
          .addClass(RepositoryAggregateRepository.class)
          .addClass(RepositoryWorkflowService.class)
          .addClass(PhaseTwoRecorder.class)
          .addClass(ActiveTaskAwarenessSource.class)
          .addClass(SingleTaskWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TestProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("dummy-adapter.two-phase-commit", "true")
      // the dummy adapter then reads the aggregate in phase two, like a remote BPMS
      .overrideConfigKey("dummy-adapter.read-aggregate-in-phase-two", "true")
      .overrideConfigKey("vanillabp.outbox.poll-interval", "PT0.5S")
      .overrideConfigKey("vanillabp.outbox.attempt-frequency", "PT0.5S");

  @Inject
  RepositoryWorkflowService workflowService;

  @Inject
  RepositoryAggregateRepository repository;

  @Inject
  PhaseTwoRecorder recorder;

  @Test
  @DisplayName("Phase two can read the aggregate: VanillaBP provides transaction and request context")
  public void phaseTwoReadsTheAggregateOfAJpaApplication() throws Exception {

    QuarkusTransaction
        .requiringNew()
        .run(() -> workflowService.startWorkflow("phase-two-start"));

    assertTrue(
        recorder.awaitStartedWorkflow("phase-two-start", 30_000),
        "phase two of starting the workflow never arrived - it failed on the dispatcher's thread");

    final var stored = QuarkusTransaction
        .requiringNew()
        .call(() -> repository.findById("phase-two-start"));
    assertNotNull(stored);
    assertEquals("started", stored.getStatus());

    QuarkusTransaction
        .requiringNew()
        .run(() -> workflowService.completeTask("phase-two-start", "Activity_Process"));

    assertTrue(
        recorder.awaitCompletedTask("phase-two-start", "Activity_Process", 30_000),
        "phase two of completing the task never arrived - it failed on the dispatcher's thread");

  }

}
