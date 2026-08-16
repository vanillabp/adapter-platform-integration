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
import io.vanillabp.integration.test.persistence.MongoRepositoryAggregate;
import io.vanillabp.integration.test.persistence.MongoRepositoryAggregateRepository;
import io.vanillabp.integration.test.persistence.MongoRepositoryWorkflowService;
import io.vanillabp.integration.test.persistence.PhaseTwoRecorder;
import io.vanillabp.integration.test.persistence.SingleTaskWiringSource;
import io.vanillabp.integration.test.utils.SuppressOutputExtension;
import jakarta.inject.Inject;

/**
 * Story 67 for the second store: the MongoDB outbox dispatcher runs phase two on its
 * own thread as well, and the same promise applies there - VanillaBP provides the
 * transaction and the active CDI request context its call back into the application
 * needs. MongoDB itself needs no session, so this test guards the routing rather
 * than a reproduction: both dispatched operations have to arrive.
 */
@ExtendWith(SuppressOutputExtension.class)
public class PhaseTwoMongoContextTest {

  @RegisterExtension
  static final QuarkusExtensionTest extensionTest = new QuarkusExtensionTest()
      .withApplicationRoot(jar -> jar
          .addAsResource("application.yaml")
          .addClass(MongoRepositoryAggregate.class)
          .addClass(MongoRepositoryAggregateRepository.class)
          .addClass(MongoRepositoryWorkflowService.class)
          .addClass(PhaseTwoRecorder.class)
          .addClass(ActiveTaskAwarenessSource.class)
          .addClass(SingleTaskWiringSource.class)
          .addAsResource(new StringAsset("not parsed by the dummy adapter"), "processes/dummy/TestProcess.bpmn")
          .addAsResource("workflow-module-descriptor/workflow-module", "META-INF/workflow-module"))
      .overrideConfigKey("dummy-adapter.two-phase-commit", "true")
      // the dummy adapter then reads the aggregate in phase two, like a remote BPMS
      .overrideConfigKey("dummy-adapter.read-aggregate-in-phase-two", "true")
      .overrideConfigKey("quarkus.mongodb.database", "phase-two-context-it")
      .overrideConfigKey("vanillabp.outbox.poll-interval", "PT0.5S")
      .overrideConfigKey("vanillabp.outbox.attempt-frequency", "PT0.5S");

  @Inject
  MongoRepositoryWorkflowService workflowService;

  @Inject
  MongoRepositoryAggregateRepository repository;

  @Inject
  PhaseTwoRecorder recorder;

  @Test
  @DisplayName("Phase two of the MongoDB outbox reads the aggregate as well")
  public void phaseTwoReadsTheAggregateOfAMongoApplication() throws Exception {

    QuarkusTransaction
        .requiringNew()
        .run(() -> workflowService.startWorkflow("phase-two-start"));

    assertTrue(
        recorder.awaitStartedWorkflow("phase-two-start", 30_000),
        "phase two of starting the workflow never arrived - it failed on the dispatcher's thread");

    final var stored = repository.findById("phase-two-start");
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
